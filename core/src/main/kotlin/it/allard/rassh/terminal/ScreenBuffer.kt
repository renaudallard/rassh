package it.allard.rassh.terminal

/** The visible rows of a screen and the lines scrolled off its top. */
class ScreenBuffer(columns: Int, rows: Int, private val maxHistory: Int) {
    var columns = columns
        private set
    var rows = rows
        private set
    private var lines = Array(rows) { TerminalRow(columns, TextStyle.NORMAL) }
    private val history = ArrayDeque<TerminalRow>()

    /** Total number of lines ever added to the history. */
    var historyAdded = 0L
        private set

    /** Cursor saved by DECSC, per buffer as in xterm. */
    var saved: SavedCursor? = null

    val historySize: Int
        get() = history.size

    /** Row y, negative values index the history. */
    fun row(y: Int): TerminalRow =
        if (y >= 0) lines[y] else history[history.size + y]

    fun clearHistory() {
        history.clear()
    }

    /** Scroll rows [top, bottom) up by n. */
    fun scrollUp(top: Int, bottom: Int, n: Int, style: Long, keep: Boolean) {
        for (i in 0 until minOf(n, bottom - top)) {
            val gone = lines[top]
            System.arraycopy(lines, top + 1, lines, top, bottom - top - 1)
            if (keep && maxHistory > 0) {
                history.addLast(gone)
                historyAdded++
                if (history.size > maxHistory)
                    history.removeFirst()
                lines[bottom - 1] = TerminalRow(columns, style)
            } else {
                gone.clear(0, columns, style)
                gone.wrapped = false
                lines[bottom - 1] = gone
            }
        }
    }

    /** Scroll rows [top, bottom) down by n. */
    fun scrollDown(top: Int, bottom: Int, n: Int, style: Long) {
        for (i in 0 until minOf(n, bottom - top)) {
            val gone = lines[bottom - 1]
            System.arraycopy(lines, top, lines, top + 1, bottom - top - 1)
            gone.clear(0, columns, style)
            gone.wrapped = false
            lines[top] = gone
        }
    }

    /**
     * Change the size of the screen. Rows below the cursor are dropped
     * first, then rows at the top move to the history. Returns the new
     * cursor row.
     */
    fun resize(columns: Int, rows: Int, cursorY: Int): Int {
        val shift = maxOf(0, cursorY + 1 - rows)
        if (shift > 0)
            scrollUp(0, this.rows, shift, TextStyle.NORMAL, true)
        lines = Array(rows) { y ->
            if (y < this.rows) lines[y] else TerminalRow(this.columns, TextStyle.NORMAL)
        }
        for (line in lines)
            line.resize(columns, TextStyle.NORMAL)
        this.columns = columns
        this.rows = rows
        return cursorY - shift
    }
}

class SavedCursor(
    val x: Int,
    val y: Int,
    val style: Long,
    val originMode: Boolean,
    val g0Graphics: Boolean,
    val g1Graphics: Boolean,
    val useG1: Boolean,
)
