package it.allard.rassh.terminal

import it.allard.rassh.terminal.TerminalRow.Companion.WIDE_TAIL

/** Receives what the terminal sends back to the program and state changes. */
interface TerminalClient {
    fun write(data: ByteArray)

    fun titleChanged(title: String) {}
}

/**
 * An xterm compatible terminal emulator. Bytes from the program are
 * passed to feed(), the result is read through row() and the cursor
 * properties. Callers must synchronize access between threads.
 */
class Terminal(
    columns: Int,
    rows: Int,
    private val client: TerminalClient,
    historySize: Int = 5000,
) {
    var columns = columns
        private set
    var rows = rows
        private set

    private val main = ScreenBuffer(columns, rows, historySize)
    private val alt = ScreenBuffer(columns, rows, 0)
    private var screen = main

    var title = ""
        private set
    var cursorX = 0
        private set
    var cursorY = 0
        private set
    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var bracketedPaste = false
        private set

    private var wrapPending = false
    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    private var newLineMode = false
    private var top = 0
    private var bottom = rows
    private var tabStops = defaultTabs(columns)

    private var fg = TextStyle.COLOR_DEFAULT
    private var bg = TextStyle.COLOR_DEFAULT
    private var flags = 0
    private var style = TextStyle.NORMAL

    private var g0Graphics = false
    private var g1Graphics = false
    private var useG1 = false
    private var lastChar = 0

    /* Parser state. */
    private var state = GROUND
    private var utf8Code = 0
    private var utf8Remaining = 0
    private var utf8Min = 0
    private var intermediate = 0
    private var prefix = 0
    private val params = IntArray(MAX_PARAMS)
    private val subParam = BooleanArray(MAX_PARAMS)
    private var nparams = 0
    private var param = -1
    private var colon = false
    private var stringEscape = false
    private val osc = StringBuilder()

    val isAltScreen: Boolean
        get() = screen === alt

    val historySize: Int
        get() = screen.historySize

    /** Lines added to the history so far, to keep a scrolled view in place. */
    val historyAdded: Long
        get() = main.historyAdded

    /** Row y of the screen, negative values index the history. */
    fun row(y: Int): TerminalRow = screen.row(y)

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xff
            if (utf8Remaining > 0) {
                if (b and 0xc0 == 0x80) {
                    utf8Code = (utf8Code shl 6) or (b and 0x3f)
                    if (--utf8Remaining == 0) {
                        val c = utf8Code
                        process(if (c < utf8Min || c > 0x10ffff || c in 0xd800..0xdfff) REPLACEMENT else c)
                    }
                    continue
                }
                utf8Remaining = 0
                process(REPLACEMENT)
            }
            when {
                b < 0x80 -> process(b)
                b and 0xe0 == 0xc0 -> startUtf8(b and 0x1f, 1, 0x80)
                b and 0xf0 == 0xe0 -> startUtf8(b and 0x0f, 2, 0x800)
                b and 0xf8 == 0xf0 -> startUtf8(b and 0x07, 3, 0x10000)
                else -> process(REPLACEMENT)
            }
        }
    }

    /** Forget a partly received sequence or character, to write text of our own. */
    fun resetParser() {
        state = GROUND
        utf8Remaining = 0
        stringEscape = false
    }

    private fun moveSaved(buffer: ScreenBuffer, y: Int, columns: Int, rows: Int) {
        val s = buffer.saved ?: return
        buffer.saved = SavedCursor(minOf(s.x, columns - 1), y.coerceIn(0, rows - 1), s.style,
            s.originMode, s.g0Graphics, s.g1Graphics, s.useG1)
    }

    fun resize(columns: Int, rows: Int) {
        if (columns < 1 || rows < 1 || columns == this.columns && rows == this.rows)
            return
        val other = if (screen === main) alt else main
        val oldY = cursorY
        cursorY = screen.resize(columns, rows, cursorY)
        /* The saved cursor stays on its line, which moved with the cursor's. */
        screen.saved?.let { moveSaved(screen, it.y + cursorY - oldY, columns, rows) }
        /* Without a saved cursor, as after 47 or 1047, nothing is dropped: the top goes to the history. */
        val y = other.resize(columns, rows, other.saved?.y ?: (this.rows - 1))
        if (other.saved != null) moveSaved(other, y, columns, rows)
        val tabs = defaultTabs(columns)
        tabStops.copyInto(tabs, 0, 0, minOf(this.columns, columns))
        tabStops = tabs
        this.columns = columns
        this.rows = rows
        cursorX = minOf(cursorX, columns - 1)
        top = 0
        bottom = rows
        wrapPending = false
    }

    /**
     * Text between two cells, inclusive. Rows may be negative to
     * reach the history. Wrapped rows are joined.
     */
    fun text(startY: Int, startX: Int, endY: Int, endX: Int): String {
        val sb = StringBuilder()
        for (y in startY..endY) {
            if (y < -screen.historySize || y >= rows) continue
            val row = row(y)
            /* Starting on the right half of a wide character takes all of it. */
            val from = if (y != startY) 0
                else if (startX in 1 until row.columns && row.text[startX] == WIDE_TAIL) startX - 1
                else startX
            val to = if (y == endY) endX + 1 else row.columns
            /* A space at a wrap is in the middle of the line, it stays. */
            val goesOn = y != endY && row.wrapped
            sb.append(row.text(from, to, trim = !goesOn))
            if (y != endY && !goesOn) sb.append('\n')
        }
        return sb.toString()
    }

    private fun startUtf8(bits: Int, remaining: Int, min: Int) {
        utf8Code = bits
        utf8Remaining = remaining
        utf8Min = min
    }

    private fun process(c: Int) {
        if (c < 0x20 || c == 0x7f) {
            control(c)
            return
        }
        when (state) {
            GROUND -> print(c)
            ESCAPE -> escape(c)
            ESCAPE_INTERMEDIATE -> escapeIntermediate(c)
            CSI -> csi(c)
            OSC -> {
                if (stringEscape) {
                    stringEscape = false
                    state = GROUND
                    if (c == '\\'.code) finishOsc() else escape(c)
                } else if (osc.length < MAX_OSC) {
                    osc.appendCodePoint(c)
                }
            }
            STRING -> {
                if (stringEscape) {
                    stringEscape = false
                    state = GROUND
                    if (c != '\\'.code) escape(c)
                }
            }
        }
    }

    private fun control(c: Int) {
        if (state == OSC || state == STRING) {
            when (c) {
                ESC -> stringEscape = true
                BEL -> {
                    if (state == OSC) finishOsc()
                    state = GROUND
                }
                CAN, SUB -> state = GROUND
            }
            return
        }
        when (c) {
            ESC -> {
                intermediate = 0
                state = ESCAPE
            }
            CAN, SUB -> state = GROUND
            0x08 -> {
                wrapPending = false
                if (cursorX > 0) cursorX--
            }
            0x09 -> tabForward(1)
            0x0a, 0x0b, 0x0c -> {
                index()
                if (newLineMode) cursorX = 0
            }
            0x0d -> {
                cursorX = 0
                wrapPending = false
            }
            0x0e -> useG1 = true
            0x0f -> useG1 = false
        }
    }

    private fun print(c0: Int) {
        val c = if ((if (useG1) g1Graphics else g0Graphics) && c0 in 0x5f..0x7e)
            DEC_GRAPHICS[c0 - 0x5f].code else c0
        val width = WcWidth.width(c)
        if (width == 0 || width > columns) return
        lastChar = c0
        if (wrapPending && autowrap) newLine()
        wrapPending = false
        if (width == 2 && cursorX == columns - 1) {
            if (!autowrap) return
            val row = screen.row(cursorY)
            splitAt(row, cursorX)
            row.clear(cursorX, columns, eraseStyle())
            newLine()
        }
        val row = screen.row(cursorY)
        if (insertMode) insertCells(row, width)
        splitAt(row, cursorX)
        splitAt(row, cursorX + width)
        row.text[cursorX] = c
        row.style[cursorX] = style
        if (width == 2) {
            row.text[cursorX + 1] = WIDE_TAIL
            row.style[cursorX + 1] = style
        }
        cursorX += width
        if (cursorX >= columns) {
            cursorX = columns - 1
            wrapPending = autowrap
        }
    }

    /** Wrap to the start of the next line. */
    private fun newLine() {
        screen.row(cursorY).wrapped = true
        cursorX = 0
        index()
    }

    /** Blank a wide character crossing the boundary between cells x - 1 and x. */
    private fun splitAt(row: TerminalRow, x: Int) {
        if (x in 1 until columns && row.text[x] == WIDE_TAIL) {
            row.text[x - 1] = 0
            row.text[x] = 0
        }
    }

    private fun eraseStyle(): Long = TextStyle.encode(TextStyle.COLOR_DEFAULT, bg, 0)

    private fun index() {
        wrapPending = false
        if (cursorY == bottom - 1)
            screen.scrollUp(top, bottom, 1, eraseStyle(), top == 0)
        else if (cursorY < rows - 1)
            cursorY++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == top)
            screen.scrollDown(top, bottom, 1, eraseStyle())
        else if (cursorY > 0)
            cursorY--
    }

    private fun escape(c: Int) {
        state = GROUND
        when (c) {
            in 0x20..0x2f -> {
                intermediate = c
                state = ESCAPE_INTERMEDIATE
            }
            '['.code -> {
                nparams = 0
                param = -1
                colon = false
                prefix = 0
                intermediate = 0
                state = CSI
            }
            ']'.code -> {
                osc.setLength(0)
                stringEscape = false
                state = OSC
            }
            'P'.code, 'X'.code, '^'.code, '_'.code -> {
                stringEscape = false
                state = STRING
            }
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> index()
            'E'.code -> {
                cursorX = 0
                index()
            }
            'M'.code -> reverseIndex()
            'H'.code -> tabStops[cursorX] = true
            'c'.code -> reset()
        }
    }

    private fun escapeIntermediate(c: Int) {
        if (c in 0x20..0x2f) return
        state = GROUND
        when (intermediate) {
            '('.code -> g0Graphics = c == '0'.code
            ')'.code -> g1Graphics = c == '0'.code
            '#'.code -> if (c == '8'.code) alignmentTest()
        }
    }

    private fun csi(c: Int) {
        when (c) {
            in '0'.code..'9'.code ->
                param = minOf(maxOf(param, 0) * 10 + (c - '0'.code), MAX_PARAM_VALUE)
            ';'.code, ':'.code -> {
                pushParam()
                colon = c == ':'.code
            }
            in 0x3c..0x3f -> if (nparams == 0 && param < 0) prefix = c
            in 0x20..0x2f -> intermediate = c
            in 0x40..0x7e -> {
                pushParam()
                state = GROUND
                dispatchCsi(c)
            }
        }
    }

    private fun pushParam() {
        if (nparams < MAX_PARAMS) {
            params[nparams] = param
            subParam[nparams] = colon
            nparams++
        }
        param = -1
        colon = false
    }

    private fun arg(i: Int, default: Int): Int =
        if (i < nparams && params[i] >= 0) params[i] else default

    private fun count(i: Int): Int = maxOf(1, arg(i, 1))

    private fun dispatchCsi(c: Int) {
        if (prefix == '?'.code) {
            if (intermediate != 0) return
            when (c) {
                'h'.code -> for (i in 0 until nparams) setPrivateMode(params[i], true)
                'l'.code -> for (i in 0 until nparams) setPrivateMode(params[i], false)
            }
            return
        }
        if (prefix == '>'.code) {
            if (c == 'c'.code && intermediate == 0) reply("\u001b[>0;0;0c")
            return
        }
        if (prefix != 0) return
        if (intermediate != 0) {
            if (intermediate == '!'.code && c == 'p'.code) softReset()
            return
        }
        when (c) {
            '@'.code -> insertCells(screen.row(cursorY), count(0))
            'A'.code -> cursorUp(count(0))
            'B'.code, 'e'.code -> cursorDown(count(0))
            'C'.code, 'a'.code -> setCursorX(cursorX + count(0))
            'D'.code -> setCursorX(cursorX - count(0))
            'E'.code -> {
                cursorDown(count(0))
                cursorX = 0
            }
            'F'.code -> {
                cursorUp(count(0))
                cursorX = 0
            }
            'G'.code, '`'.code -> setCursorX(count(0) - 1)
            'H'.code, 'f'.code -> setCursor(count(1) - 1, count(0) - 1)
            'I'.code -> tabForward(count(0))
            'J'.code -> eraseDisplay(arg(0, 0))
            'K'.code -> eraseLine(arg(0, 0))
            'L'.code -> if (cursorY in top until bottom) {
                screen.scrollDown(cursorY, bottom, count(0), eraseStyle())
                cursorX = 0
                wrapPending = false
            }
            'M'.code -> if (cursorY in top until bottom) {
                screen.scrollUp(cursorY, bottom, count(0), eraseStyle(), false)
                cursorX = 0
                wrapPending = false
            }
            'P'.code -> deleteCells(count(0))
            'S'.code -> screen.scrollUp(top, bottom, count(0), eraseStyle(), false)
            'T'.code -> if (nparams <= 1) screen.scrollDown(top, bottom, count(0), eraseStyle())
            'X'.code -> eraseCells(cursorX, minOf(columns, cursorX + count(0)))
            'Z'.code -> tabBackward(count(0))
            /* More than a screenful looks the same and costs the host nothing to ask. */
            'b'.code -> if (lastChar != 0) {
                val ch = lastChar
                repeat(minOf(count(0), columns * rows)) { print(ch) }
            }
            'c'.code -> if (arg(0, 0) == 0) reply("\u001b[?62;22c")
            'd'.code -> setCursor(cursorX, count(0) - 1)
            'g'.code -> when (arg(0, 0)) {
                0 -> tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h'.code, 'l'.code -> for (i in 0 until nparams) {
                when (params[i]) {
                    4 -> insertMode = c == 'h'.code
                    20 -> newLineMode = c == 'h'.code
                }
            }
            'm'.code -> selectGraphicRendition()
            'n'.code -> when (arg(0, 0)) {
                5 -> reply("\u001b[0n")
                6 -> {
                    val y = if (originMode) cursorY - top else cursorY
                    reply("\u001b[${y + 1};${cursorX + 1}R")
                }
            }
            'r'.code -> {
                val t = count(0) - 1
                val b = minOf(arg(1, rows).let { if (it == 0) rows else it }, rows)
                if (t < b - 1) {
                    top = t
                    bottom = b
                    setCursor(0, 0)
                }
            }
            's'.code -> saveCursor()
            'u'.code -> restoreCursor()
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            6 -> {
                originMode = on
                setCursor(0, 0)
            }
            7 -> autowrap = on
            25 -> cursorVisible = on
            47 -> useAltScreen(on)
            1047 -> {
                if (!on && screen === alt) eraseDisplay(2)
                useAltScreen(on)
            }
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) {
                saveCursor()
                useAltScreen(true)
                eraseDisplay(2)
            } else {
                useAltScreen(false)
                restoreCursor()
            }
            2004 -> bracketedPaste = on
        }
    }

    private fun useAltScreen(on: Boolean) {
        screen = if (on) alt else main
        wrapPending = false
    }

    private fun selectGraphicRendition() {
        var i = 0
        while (i < nparams) {
            val p = maxOf(params[i], 0)
            when (p) {
                0 -> {
                    fg = TextStyle.COLOR_DEFAULT
                    bg = TextStyle.COLOR_DEFAULT
                    flags = 0
                }
                1 -> flags = flags or TextStyle.BOLD
                2 -> flags = flags or TextStyle.DIM
                3 -> flags = flags or TextStyle.ITALIC
                4 -> {
                    if (i + 1 < nparams && subParam[i + 1]) {
                        i++
                        flags = if (params[i] == 0) flags and TextStyle.UNDERLINE.inv()
                        else flags or TextStyle.UNDERLINE
                    } else {
                        flags = flags or TextStyle.UNDERLINE
                    }
                }
                5, 6 -> flags = flags or TextStyle.BLINK
                7 -> flags = flags or TextStyle.INVERSE
                8 -> flags = flags or TextStyle.INVISIBLE
                9 -> flags = flags or TextStyle.STRIKETHROUGH
                21 -> flags = flags or TextStyle.UNDERLINE
                22 -> flags = flags and (TextStyle.BOLD or TextStyle.DIM).inv()
                23 -> flags = flags and TextStyle.ITALIC.inv()
                24 -> flags = flags and TextStyle.UNDERLINE.inv()
                25 -> flags = flags and TextStyle.BLINK.inv()
                27 -> flags = flags and TextStyle.INVERSE.inv()
                28 -> flags = flags and TextStyle.INVISIBLE.inv()
                29 -> flags = flags and TextStyle.STRIKETHROUGH.inv()
                in 30..37 -> fg = p - 30
                38 -> {
                    val (color, last) = extendedColor(i)
                    if (color >= 0) fg = color
                    i = last
                }
                39 -> fg = TextStyle.COLOR_DEFAULT
                in 40..47 -> bg = p - 40
                48 -> {
                    val (color, last) = extendedColor(i)
                    if (color >= 0) bg = color
                    i = last
                }
                49 -> bg = TextStyle.COLOR_DEFAULT
                /* Underline color, parsed for its arguments but not drawn. */
                58 -> i = extendedColor(i).second
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
            }
            /* Colon arguments of a code not handled above are not attributes. */
            while (i + 1 < nparams && subParam[i + 1]) i++
            i++
        }
        style = TextStyle.encode(fg, bg, flags)
    }

    /**
     * Parse the color following parameter i (38 or 48), in either the
     * 5;n and 2;r;g;b forms or their colon separated variants.
     * Returns the color, or -1, and the index of the last parameter used.
     */
    private fun extendedColor(i: Int): Pair<Int, Int> {
        if (i + 1 >= nparams) return Pair(-1, i)
        if (subParam[i + 1]) {
            var j = i + 1
            while (j < nparams && subParam[j]) j++
            val n = j - i - 1
            val color = when {
                params[i + 1] == 5 && n >= 2 -> indexedColor(params[i + 2])
                params[i + 1] == 2 && n >= 4 -> {
                    /* An optional color space id precedes the components. */
                    val k = if (n >= 5) i + 3 else i + 2
                    TextStyle.rgb(params[k], params[k + 1], params[k + 2])
                }
                else -> -1
            }
            return Pair(color, j - 1)
        }
        return when (params[i + 1]) {
            5 -> if (i + 2 < nparams) Pair(indexedColor(params[i + 2]), i + 2)
                else Pair(-1, nparams - 1)
            2 -> if (i + 4 < nparams)
                Pair(TextStyle.rgb(params[i + 2], params[i + 3], params[i + 4]), i + 4)
                else Pair(-1, nparams - 1)
            else -> Pair(-1, i + 1)
        }
    }

    private fun indexedColor(n: Int): Int = if (n in 0..255) n else -1

    private fun finishOsc() {
        val s = osc.toString()
        osc.setLength(0)
        val semi = s.indexOf(';')
        if (semi < 0) return
        when (s.substring(0, semi)) {
            "0", "2" -> {
                title = s.substring(semi + 1).take(MAX_TITLE)
                client.titleChanged(title)
            }
        }
    }

    private fun reply(s: String) {
        client.write(s.toByteArray())
    }

    private fun cursorUp(n: Int) {
        val limit = if (cursorY >= top) top else 0
        cursorY = maxOf(limit, cursorY - n)
        wrapPending = false
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorY < bottom) bottom - 1 else rows - 1
        cursorY = minOf(limit, cursorY + n)
        wrapPending = false
    }

    private fun setCursorX(x: Int) {
        cursorX = x.coerceIn(0, columns - 1)
        wrapPending = false
    }

    private fun setCursor(x: Int, y: Int) {
        cursorX = x.coerceIn(0, columns - 1)
        cursorY = if (originMode) (y + top).coerceIn(top, bottom - 1)
        else y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    /* Beyond the width every tab lands at the edge, n is up to 65535. */
    private fun tabForward(n: Int) {
        wrapPending = false
        repeat(minOf(n, columns)) {
            var x = cursorX + 1
            while (x < columns - 1 && !tabStops[x]) x++
            cursorX = minOf(x, columns - 1)
        }
    }

    private fun tabBackward(n: Int) {
        wrapPending = false
        repeat(minOf(n, columns)) {
            var x = cursorX - 1
            while (x > 0 && !tabStops[x]) x--
            cursorX = maxOf(x, 0)
        }
    }

    private fun eraseCells(from: Int, to: Int) {
        if (from >= to) return
        val row = screen.row(cursorY)
        splitAt(row, from)
        splitAt(row, to)
        row.clear(from, to, eraseStyle())
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> {
                eraseCells(cursorX, columns)
                screen.row(cursorY).wrapped = false
            }
            1 -> eraseCells(0, cursorX + 1)
            2 -> {
                eraseCells(0, columns)
                screen.row(cursorY).wrapped = false
            }
        }
    }

    private fun eraseDisplay(mode: Int) {
        val style = eraseStyle()
        when (mode) {
            0 -> {
                eraseLine(0)
                for (y in cursorY + 1 until rows) clearRow(y, style)
            }
            1 -> {
                for (y in 0 until cursorY) clearRow(y, style)
                eraseLine(1)
            }
            2 -> for (y in 0 until rows) clearRow(y, style)
            3 -> screen.clearHistory()
        }
    }

    private fun clearRow(y: Int, style: Long) {
        val row = screen.row(y)
        row.clear(0, columns, style)
        row.wrapped = false
    }

    private fun insertCells(row: TerminalRow, n0: Int) {
        val n = minOf(n0, columns - cursorX)
        splitAt(row, cursorX)
        splitAt(row, columns - n)
        System.arraycopy(row.text, cursorX, row.text, cursorX + n, columns - cursorX - n)
        System.arraycopy(row.style, cursorX, row.style, cursorX + n, columns - cursorX - n)
        row.clear(cursorX, cursorX + n, eraseStyle())
        wrapPending = false
    }

    private fun deleteCells(n0: Int) {
        val row = screen.row(cursorY)
        val n = minOf(n0, columns - cursorX)
        splitAt(row, cursorX)
        splitAt(row, cursorX + n)
        System.arraycopy(row.text, cursorX + n, row.text, cursorX, columns - cursorX - n)
        System.arraycopy(row.style, cursorX + n, row.style, cursorX, columns - cursorX - n)
        row.clear(columns - n, columns, eraseStyle())
        wrapPending = false
    }

    private fun saveCursor() {
        screen.saved = SavedCursor(cursorX, cursorY, style, originMode,
            g0Graphics, g1Graphics, useG1)
    }

    private fun restoreCursor() {
        val s = screen.saved
        if (s == null) {
            setCursor(0, 0)
            return
        }
        cursorX = minOf(s.x, columns - 1)
        cursorY = minOf(s.y, rows - 1)
        style = s.style
        fg = TextStyle.fg(style)
        bg = TextStyle.bg(style)
        flags = TextStyle.flags(style)
        originMode = s.originMode
        g0Graphics = s.g0Graphics
        g1Graphics = s.g1Graphics
        useG1 = s.useG1
        wrapPending = false
    }

    private fun alignmentTest() {
        for (y in 0 until rows) {
            val row = screen.row(y)
            row.text.fill('E'.code)
            row.style.fill(TextStyle.NORMAL)
            row.wrapped = false
        }
        top = 0
        bottom = rows
        setCursor(0, 0)
    }

    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autowrap = true
        applicationCursorKeys = false
        top = 0
        bottom = rows
        g0Graphics = false
        g1Graphics = false
        useG1 = false
        fg = TextStyle.COLOR_DEFAULT
        bg = TextStyle.COLOR_DEFAULT
        flags = 0
        style = TextStyle.NORMAL
        screen.saved = null
        wrapPending = false
    }

    private fun reset() {
        softReset()
        newLineMode = false
        bracketedPaste = false
        screen = main
        main.saved = null
        alt.saved = null
        main.clearHistory()
        for (y in 0 until rows) {
            clearRow(y, TextStyle.NORMAL)
            alt.row(y).clear(0, columns, TextStyle.NORMAL)
            alt.row(y).wrapped = false
        }
        tabStops = defaultTabs(columns)
        cursorX = 0
        cursorY = 0
        lastChar = 0
    }

    companion object {
        private const val GROUND = 0
        private const val ESCAPE = 1
        private const val ESCAPE_INTERMEDIATE = 2
        private const val CSI = 3
        private const val OSC = 4
        private const val STRING = 5

        private const val BEL = 0x07
        private const val CAN = 0x18
        private const val SUB = 0x1a
        private const val ESC = 0x1b

        private const val REPLACEMENT = 0xfffd
        private const val MAX_PARAMS = 32
        private const val MAX_PARAM_VALUE = 65535
        private const val MAX_OSC = 4096
        private const val MAX_TITLE = 256

        /* DEC special graphics for 0x5f to 0x7e. */
        private const val DEC_GRAPHICS =
            " ◆▒␉␌␍␊°±␤␋" +
                "┘┐┌└┼⎺⎻─⎼⎽" +
                "├┤┴┬│≤≥π≠£·"

        private fun defaultTabs(columns: Int) = BooleanArray(columns) { it > 0 && it % 8 == 0 }
    }
}
