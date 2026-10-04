package it.allard.rassh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import it.allard.rassh.terminal.KeyEncoder
import it.allard.rassh.terminal.KeyEncoder.Key
import it.allard.rassh.terminal.Terminal
import it.allard.rassh.terminal.TerminalRow
import it.allard.rassh.terminal.TextStyle
import kotlin.math.abs

/** Draws the terminal of a session and sends it keys and touches. */
class TerminalView(context: Context, attrs: AttributeSet?) : View(context, attrs),
    ExtraKeysView.Listener {
    var extraKeys: ExtraKeysView? = null
    var onCloseRequest: (() -> Unit)? = null

    /* The accent of a dead key waiting for the next key, see onKeyDown(). */
    private var deadAccent = 0

    /* The terminal screen sets the size of new sessions, a smaller view does not. */
    var savesSize = true

    var session: Session? = null
        set(value) {
            field = value
            scrollOffset = 0
            deadAccent = 0
            clearSelection()
            lastHistoryAdded = value?.terminal?.let { synchronized(it) { it.historyAdded } } ?: 0
            resizeTerminal()
            invalidate()
        }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var fontSize = prefs.getFloat(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)
    /* A bundled font, OEM themes may replace even Typeface.MONOSPACE. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.dejavu_sans_mono)
    }
    private var cellWidth = 1f
    private var cellHeight = 1
    private var baseline = 0
    private val runChars = CharArray(256)
    private val oneChar = CharArray(2)

    /* The default colors follow the phone theme, the palette does not. */
    private val foreground = context.themeColor(android.R.attr.textColorPrimary)
    private val background = context.themeColor(android.R.attr.colorBackground)
    private val selectionColor = context.themeColor(android.R.attr.colorAccent) and 0xffffff or SELECTION_ALPHA

    /* Lines scrolled back into the history. */
    private var scrollOffset = 0
    private var lastHistoryAdded = 0L
    private var scrollRemainder = 0f

    /* Selection between two cells, rows are terminal rows, negative in the history. */
    private var hasSelection = false
    private var dragging = false
    private var anchorX = 0
    private var anchorY = 0
    private var pointX = 0
    private var pointY = 0
    /* Made on the alternate screen, whose lines never go into the history. */
    private var selectionOnAlt = false
    private var actionMode: ActionMode? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollLines(dy)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (session == null) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            actionMode?.finish()
            val (x, y) = cellAt(e)
            val t = session?.terminal ?: return
            selectionOnAlt = synchronized(t) { t.isAltScreen }
            anchorX = x
            anchorY = y
            pointX = x
            pointY = y
            hasSelection = true
            dragging = true
            invalidate()
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setFontSize(fontSize * d.scaleFactor)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            prefs.edit().putFloat(PREF_FONT_SIZE, fontSize).apply()
        }
    })

    init {
        updateMetrics()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        resizeTerminal()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        val s = session ?: return
        val t = s.terminal
        synchronized(t) {
            followHistory(t)
            scrollOffset = scrollOffset.coerceIn(0, t.historySize)
            for (r in 0 until t.rows)
                drawRow(canvas, t.row(r - scrollOffset), t.columns, (r * cellHeight).toFloat())
            if (scrollOffset == 0 && t.cursorVisible && s.isRunning)
                drawCursor(canvas, t.row(t.cursorY), t.cursorX, t.cursorY)
            if (hasSelection) drawSelection(canvas, t.columns, t.rows)
        }
    }

    private fun drawRow(canvas: Canvas, row: TerminalRow, columns: Int, top: Float) {
        val n = minOf(row.columns, columns)
        var x = 0
        while (x < n) {
            val style = row.style[x]
            var end = x + 1
            while (end < n && row.style[end] == style) end++
            drawRun(canvas, row, x, end, style, top)
            x = end
        }
    }

    private fun drawRun(canvas: Canvas, row: TerminalRow, from: Int, to: Int, style: Long, top: Float) {
        val flags = TextStyle.flags(style)
        var fg = color(TextStyle.fg(style), foreground)
        var bg = color(TextStyle.bg(style), background)
        if (flags and TextStyle.INVERSE != 0) {
            val tmp = fg
            fg = bg
            bg = tmp
        }
        if (bg != background) {
            setPaint(bg, 0)
            canvas.drawRect(from * cellWidth, top, to * cellWidth, top + cellHeight, paint)
        }
        if (flags and TextStyle.INVISIBLE != 0) return
        setPaint(fg, flags)
        val y = top + baseline
        var start = from
        var count = 0
        for (i in from until to) {
            val c = row.text[i]
            if (c == 0 || c in 0x20..0x7e) {
                if (count == runChars.size) {
                    canvas.drawText(runChars, 0, count, start * cellWidth, y, paint)
                    count = 0
                }
                if (count == 0) start = i
                runChars[count++] = if (c == 0) ' ' else c.toChar()
                continue
            }
            if (count > 0) {
                canvas.drawText(runChars, 0, count, start * cellWidth, y, paint)
                count = 0
            }
            if (c == TerminalRow.WIDE_TAIL) continue
            val len = Character.toChars(c, oneChar, 0)
            canvas.drawText(oneChar, 0, len, i * cellWidth, y, paint)
        }
        if (count > 0) canvas.drawText(runChars, 0, count, start * cellWidth, y, paint)
    }

    private fun drawCursor(canvas: Canvas, row: TerminalRow, cursorX: Int, y: Int) {
        if (cursorX >= row.columns) return
        /* On a wide character, the cursor covers both its cells, as in xterm. */
        val x = if (cursorX > 0 && row.text[cursorX] == TerminalRow.WIDE_TAIL) cursorX - 1 else cursorX
        val cells = if (x + 1 < row.columns && row.text[x + 1] == TerminalRow.WIDE_TAIL) 2 else 1
        val left = x * cellWidth
        val top = (y * cellHeight).toFloat()
        setPaint(foreground, 0)
        canvas.drawRect(left, top, left + cells * cellWidth, top + cellHeight, paint)
        val c = row.text[x]
        if (c > 0x20) {
            setPaint(background, 0)
            val len = Character.toChars(c, oneChar, 0)
            canvas.drawText(oneChar, 0, len, left, top + baseline, paint)
        }
    }

    private fun drawSelection(canvas: Canvas, columns: Int, rows: Int) {
        val (sx, sy, ex, ey) = selection()
        setPaint(selectionColor, 0)
        for (r in 0 until rows) {
            val y = r - scrollOffset
            if (y < sy || y > ey) continue
            val from = if (y == sy) sx else 0
            val to = if (y == ey) ex + 1 else columns
            val top = (r * cellHeight).toFloat()
            canvas.drawRect(from * cellWidth, top, to * cellWidth, top + cellHeight, paint)
        }
    }

    private fun setPaint(color: Int, flags: Int) {
        paint.color = color
        if (flags and TextStyle.DIM != 0) paint.alpha = DIM_ALPHA
        paint.isFakeBoldText = flags and TextStyle.BOLD != 0
        paint.textSkewX = if (flags and TextStyle.ITALIC != 0) ITALIC_SKEW else 0f
        paint.isUnderlineText = flags and TextStyle.UNDERLINE != 0
        paint.isStrikeThruText = flags and TextStyle.STRIKETHROUGH != 0
    }

    private fun color(c: Int, default: Int): Int = when {
        c == TextStyle.COLOR_DEFAULT -> default
        c and TextStyle.COLOR_RGB != 0 -> Color.BLACK or (c and 0xffffff)
        else -> PALETTE[c and 0xff]
    }

    /* The cell size is in pixels, a new display size changes it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateMetrics()
        resizeTerminal()
        invalidate()
    }

    private fun updateMetrics() {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize, resources.displayMetrics)
        cellWidth = paint.measureText("M")
        val fm = paint.fontMetricsInt
        cellHeight = fm.descent - fm.ascent
        baseline = -fm.ascent
    }

    private fun setFontSize(size: Float) {
        val s = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (s == fontSize) return
        fontSize = s
        updateMetrics()
        resizeTerminal()
        invalidate()
    }

    /** Give the session the size of this view again, another may have resized it. */
    fun refit() {
        resizeTerminal()
    }

    private fun resizeTerminal() {
        if (width == 0 || height == 0) return
        val columns = maxOf(1, (width / cellWidth).toInt())
        val rows = maxOf(1, height / cellHeight)
        session?.resize(columns, rows)
        /* New sessions start at this size, see SessionService. */
        if (savesSize && (prefs.getInt(PREF_COLUMNS, 0) != columns || prefs.getInt(PREF_ROWS, 0) != rows))
            prefs.edit().putInt(PREF_COLUMNS, columns).putInt(PREF_ROWS, rows).apply()
    }

    /*
     * Lines going into the history move the text up: keep a scrolled back
     * view and the selection on the text they showed. Called with t locked.
     */
    private fun followHistory(t: Terminal) {
        val added = (t.historyAdded - lastHistoryAdded).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        lastHistoryAdded = t.historyAdded
        if (added == 0) return
        if (scrollOffset > 0) scrollOffset += added
        /* Main screen lines, even pushed by a resize on the alternate screen, where a selection stays. */
        if (hasSelection && !selectionOnAlt) {
            anchorY -= added
            pointY -= added
        }
    }

    private fun cellAt(e: MotionEvent): Pair<Int, Int> {
        val t = session?.terminal ?: return Pair(0, 0)
        val (columns, rows) = synchronized(t) {
            followHistory(t)
            Pair(t.columns, t.rows)
        }
        val x = (e.x / cellWidth).toInt().coerceIn(0, columns - 1)
        val y = (e.y / cellHeight).toInt().coerceIn(0, rows - 1) - scrollOffset
        return Pair(x, y)
    }

    /* Selection ordered as start x, start y, end x, end y. */
    private fun selection(): IntArray =
        if (anchorY < pointY || anchorY == pointY && anchorX <= pointX) intArrayOf(anchorX, anchorY, pointX, pointY)
        else intArrayOf(pointX, pointY, anchorX, anchorY)

    private fun clearSelection() {
        hasSelection = false
        dragging = false
        val mode = actionMode
        actionMode = null
        mode?.finish()
        invalidate()
    }

    private fun showSelectionMenu() {
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_COPY, 0, R.string.copy)
                menu.add(Menu.NONE, MENU_PASTE, 1, R.string.paste)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY -> copySelection()
                    MENU_PASTE -> paste()
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (actionMode === mode) {
                    actionMode = null
                    clearSelection()
                }
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val (sx, sy, ex, ey) = selection()
                val top = (sy + scrollOffset) * cellHeight
                val bottom = (ey + scrollOffset + 1) * cellHeight
                val left = if (sy == ey) (sx * cellWidth).toInt() else 0
                val right = if (sy == ey) ((ex + 1) * cellWidth).toInt() else width
                outRect.set(left, top.coerceAtLeast(0), right, bottom.coerceAtMost(height))
            }
        }, ActionMode.TYPE_FLOATING)
    }

    private fun copySelection() {
        val t = session?.terminal ?: return
        val text = synchronized(t) {
            followHistory(t)
            val (sx, sy, ex, ey) = selection()
            t.text(sy, sx, ey, ex)
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    }

    /** Send the clipboard, bracketed when the program asked for it. */
    fun paste() {
        /* Nothing goes to an ended session, a lone newline would close it. */
        if (session?.isRunning != true) return
        val t = session?.terminal ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        /*
         * Control characters but tab and newline become spaces, as in
         * xterm: ^C or ^Z would reach the remote terminal as signals, ESC
         * could end a bracketed paste early or start a sequence.
         */
        var s = clip.getItemAt(0).coerceToText(context).toString()
            .replace("\r\n", "\r").replace('\n', '\r').replace(PASTE_CONTROLS, " ")
        if (synchronized(t) { t.bracketedPaste })
            s = "\u001b[200~$s\u001b[201~"
        if (!send(s)) context.toast(context.getString(R.string.paste_too_large))
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (hasSelection) clearSelection() else showKeyboard()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (dragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val (x, y) = cellAt(e)
                    if (x != pointX || y != pointY) {
                        pointX = x
                        pointY = y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    showSelectionMenu()
                }
                MotionEvent.ACTION_CANCEL -> clearSelection()
            }
            return true
        }
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    private fun scrollLines(dy: Float) {
        val t = session?.terminal ?: return
        scrollRemainder += dy
        val lines = (scrollRemainder / cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellHeight
        val (alt, history) = synchronized(t) { Pair(t.isAltScreen, t.historySize) }
        if (alt) {
            /* Full screen programs scroll with the cursor keys. */
            repeat(abs(lines)) { sendKey(if (lines > 0) Key.DOWN else Key.UP, 0) }
        } else {
            scrollOffset = (scrollOffset - lines).coerceIn(0, history)
            invalidate()
        }
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    fun toggleKeyboard() {
        val controller = windowInsetsController ?: return
        if (rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
            controller.hide(WindowInsets.Type.ime())
        } else {
            showKeyboard()
        }
    }

    override fun onExtraKey(action: ExtraKeysView.Action, mods: Int) {
        when (action) {
            is ExtraKeysView.Action.Special -> sendKey(action.key, mods)
            is ExtraKeysView.Action.Text -> typeText(action.text, mods)
            is ExtraKeysView.Action.Modifier -> {}
        }
    }

    /*
     * Android names the accent of a dead key with a modifier letter, ˆ for
     * the ^ key, which typed alone means the ASCII one, as for ~ and `.
     */
    private fun plainAccent(c: Int): Int = when (c) {
        0x02c6 -> '^'.code
        0x02dc -> '~'.code
        0x02cb -> '`'.code
        else -> c
    }

    private fun sendKey(key: Key, mods: Int) {
        val t = session?.terminal ?: return
        send(KeyEncoder.encode(key, mods, synchronized(t) { t.applicationCursorKeys }))
    }

    /* Latched CTRL and ALT apply to the first character, a keyboard may send a whole word. */
    private fun typeText(s: String, mods: Int) {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            sb.append(KeyEncoder.encode(if (c == '\n'.code) '\r'.code else c, if (i == 0) mods else 0))
            i += Character.charCount(c)
        }
        send(sb.toString())
    }

    /* False when the session could not take s, see Session.offer(). */
    private fun send(s: String): Boolean {
        /* Anything sent, a special key or text from the soft keyboard, ends a dead key. */
        deadAccent = 0
        val session = session ?: return true
        if (!session.isRunning) {
            /* Enter alone, a paste holding a newline must not close the session. */
            if (s == "\r") onCloseRequest?.invoke()
            return true
        }
        if (scrollOffset != 0) {
            scrollOffset = 0
            invalidate()
        }
        return session.offer(s.toByteArray())
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (editable?.isNotEmpty() == true)
                    return super.deleteSurroundingText(beforeLength, afterLength)
                /* Latched CTRL and ALT apply to the first one, as for the Backspace key. */
                val mods = extraKeys?.consumeModifiers() ?: 0
                repeat(beforeLength) { sendKey(Key.BACKSPACE, if (it == 0) mods else 0) }
                return true
            }

            private fun flush() {
                val e = editable ?: return
                val s = e.toString()
                e.clear()
                /* Clipboard text a keyboard commits may end its lines with CR LF, one Enter each. */
                if (s.isNotEmpty()) typeText(s.replace("\r\n", "\r"), extraKeys?.consumeModifiers() ?: 0)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null) return super.onKeyDown(keyCode, event)
        var mods = 0
        if (event.isShiftPressed) mods = mods or KeyEncoder.SHIFT
        if (event.isAltPressed) mods = mods or KeyEncoder.ALT
        if (event.isCtrlPressed) mods = mods or KeyEncoder.CTRL
        val key = KEYS[keyCode]
        if (key != null) {
            sendKey(key, mods or (extraKeys?.consumeModifiers() ?: 0))
            return true
        }
        /*
         * Right Alt is AltGr on many layouts: when it makes a character of
         * its own, type that and leave Alt to left Alt only.
         */
        val meta = event.metaState and KeyEvent.META_CTRL_MASK.inv()
        var c = event.getUnicodeChar(meta and KeyEvent.META_ALT_MASK.inv())
        if (meta and KeyEvent.META_ALT_RIGHT_ON != 0) {
            val altGr = event.getUnicodeChar(meta and KeyEvent.META_ALT_LEFT_ON.inv())
            if (altGr != 0 && altGr != c) {
                c = altGr
                if (meta and KeyEvent.META_ALT_LEFT_ON == 0) mods = mods and KeyEncoder.ALT.inv()
            }
        }
        if (c == 0) return super.onKeyDown(keyCode, event)
        /*
         * A dead key, ^ on many layouts, combines with the next one, a
         * space giving it alone. With Ctrl or Alt it is the accent itself,
         * and a key with them is not combined: Ctrl-C stays an interrupt.
         */
        val modified = (mods or (extraKeys?.latched ?: 0)) and (KeyEncoder.CTRL or KeyEncoder.ALT) != 0
        if (c and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (modified) {
                c = plainAccent(c and KeyCharacterMap.COMBINING_ACCENT_MASK)
            } else {
                /* A second one combines with the first, ^^ gives ^, or sends it and waits itself. */
                val accent = c and KeyCharacterMap.COMBINING_ACCENT_MASK
                val pending = deadAccent
                if (pending != 0) {
                    val composed = KeyCharacterMap.getDeadChar(pending, accent)
                    send(KeyEncoder.encode(plainAccent(if (composed != 0) composed else pending), 0))
                    if (composed != 0) return true
                }
                deadAccent = accent
                return true
            }
        }
        if (deadAccent != 0 && !modified) {
            val composed = KeyCharacterMap.getDeadChar(deadAccent, c)
            if (composed != 0) c = plainAccent(composed) else send(KeyEncoder.encode(plainAccent(deadAccent), 0))
            deadAccent = 0
        }
        mods = (mods or (extraKeys?.consumeModifiers() ?: 0)) and KeyEncoder.SHIFT.inv()
        send(KeyEncoder.encode(c, mods))
        return true
    }

    companion object {
        private val PASTE_CONTROLS = Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]")
        const val PREFS = "terminal"
        const val PREF_COLUMNS = "columns"
        const val PREF_ROWS = "rows"
        const val PREF_FONT_SIZE = "font_size"
        private const val DEFAULT_FONT_SIZE = 12f
        const val MIN_FONT_SIZE = 6f
        const val MAX_FONT_SIZE = 40f
        private const val DIM_ALPHA = 0x99
        private const val SELECTION_ALPHA = 0x66 shl 24
        private const val ITALIC_SKEW = -0.25f
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2

        private val KEYS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to Key.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Key.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to Key.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to Key.RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME to Key.HOME,
            KeyEvent.KEYCODE_MOVE_END to Key.END,
            KeyEvent.KEYCODE_PAGE_UP to Key.PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to Key.PAGE_DOWN,
            KeyEvent.KEYCODE_INSERT to Key.INSERT,
            KeyEvent.KEYCODE_FORWARD_DEL to Key.DELETE,
            KeyEvent.KEYCODE_TAB to Key.TAB,
            KeyEvent.KEYCODE_ESCAPE to Key.ESCAPE,
            KeyEvent.KEYCODE_ENTER to Key.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to Key.ENTER,
            KeyEvent.KEYCODE_DEL to Key.BACKSPACE,
            KeyEvent.KEYCODE_F1 to Key.F1,
            KeyEvent.KEYCODE_F2 to Key.F2,
            KeyEvent.KEYCODE_F3 to Key.F3,
            KeyEvent.KEYCODE_F4 to Key.F4,
            KeyEvent.KEYCODE_F5 to Key.F5,
            KeyEvent.KEYCODE_F6 to Key.F6,
            KeyEvent.KEYCODE_F7 to Key.F7,
            KeyEvent.KEYCODE_F8 to Key.F8,
            KeyEvent.KEYCODE_F9 to Key.F9,
            KeyEvent.KEYCODE_F10 to Key.F10,
            KeyEvent.KEYCODE_F11 to Key.F11,
            KeyEvent.KEYCODE_F12 to Key.F12,
        )

        /* xterm colors: 16 base colors, a 6x6x6 cube and 24 grays. */
        private val PALETTE = IntArray(256).also { p ->
            val base = intArrayOf(
                0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
                0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
            )
            for (i in 0 until 16) p[i] = Color.BLACK or base[i]
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            for (i in 0 until 216) p[16 + i] = Color.rgb(steps[i / 36], steps[i / 6 % 6], steps[i % 6])
            for (i in 0 until 24) {
                val v = 8 + 10 * i
                p[232 + i] = Color.rgb(v, v, v)
            }
        }
    }
}
