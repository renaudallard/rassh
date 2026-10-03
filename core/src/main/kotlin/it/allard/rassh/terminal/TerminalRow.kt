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

    /** Text of the cells in [from, to), without trailing blanks. */
    fun text(from: Int, to: Int): String {
        val sb = StringBuilder()
        for (x in from until minOf(to, text.size)) {
            when (val c = text[x]) {
                WIDE_TAIL -> {}
                0 -> sb.append(' ')
                else -> sb.appendCodePoint(c)
            }
        }
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        sb.setLength(end)
        return sb.toString()
    }

    companion object {
        const val WIDE_TAIL = -1
    }
}
