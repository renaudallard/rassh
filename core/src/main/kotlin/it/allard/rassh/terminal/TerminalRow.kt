package it.allard.rassh.terminal

/** A line of cells. A cell holds a code point, 0 when blank, or WIDE_TAIL. */
class TerminalRow(columns: Int, style: Long) {
    var text = IntArray(columns)
        private set
    var style = LongArray(columns) { style }
        private set

    /** The line continues on the next row because of autowrap. */
    var wrapped = false

    val columns: Int
        get() = text.size

    fun clear(from: Int, to: Int, style: Long) {
        text.fill(0, from, to)
        this.style.fill(style, from, to)
    }

    fun resize(columns: Int, style: Long) {
        if (columns == text.size) return
        if (columns < text.size && text[columns] == WIDE_TAIL)
            text[columns - 1] = 0
        val old = text.size
        text = text.copyOf(columns)
        this.style = this.style.copyOf(columns)
        if (columns > old)
            this.style.fill(style, old, columns)
        wrapped = false
    }

    /*
     * Drop the cells blank in the default style at the end, a row going
     * into the history keeps its memory for what it shows. Readers take
     * columns as the width of the row, not of the screen.
     */
    fun trimEnd() {
        var end = text.size
        while (end > 0 && text[end - 1] == 0 && style[end - 1] == TextStyle.NORMAL) end--
        if (end == text.size) return
        text = text.copyOf(end)
        style = style.copyOf(end)
    }

    /** Text of the cells in [from, to), without trailing blanks. */
    /*
     * Trailing blanks are dropped. A row going on in the next, trim false,
     * keeps the spaces written, only the cells left empty go: the end of a
     * row a wide character did not fit in, or emptied by a deletion.
     */
    fun text(from: Int, to: Int, trim: Boolean = true): String {
        val sb = StringBuilder()
        var last = minOf(to, text.size)
        if (!trim) while (last > from && text[last - 1] == 0) last--
        for (x in from until last) {
            when (val c = text[x]) {
                WIDE_TAIL -> {}
                0 -> sb.append(' ')
                else -> sb.appendCodePoint(c)
            }
        }
        if (trim) {
            var end = sb.length
            while (end > 0 && sb[end - 1] == ' ') end--
            sb.setLength(end)
        }
        return sb.toString()
    }

    companion object {
        const val WIDE_TAIL = -1
    }
}
