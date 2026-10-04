package it.allard.rassh.terminal

/** Translate keys to the byte sequences an xterm sends. */
object KeyEncoder {
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4

    enum class Key {
        UP, DOWN, RIGHT, LEFT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
        TAB, ESCAPE, ENTER, BACKSPACE,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    }

    fun encode(key: Key, mods: Int, applicationCursor: Boolean): String {
        /* xterm encodes modifiers as 1 + bitmask. */
        val m = if (mods == 0) 0 else mods + 1
        val alt = if (mods and ALT != 0) "\u001b" else ""
        return when (key) {
            Key.UP -> cursor('A', m, applicationCursor)
            Key.DOWN -> cursor('B', m, applicationCursor)
            Key.RIGHT -> cursor('C', m, applicationCursor)
            Key.LEFT -> cursor('D', m, applicationCursor)
            Key.HOME -> cursor('H', m, applicationCursor)
            Key.END -> cursor('F', m, applicationCursor)
            Key.INSERT -> tilde(2, m)
            Key.DELETE -> tilde(3, m)
            Key.PAGE_UP -> tilde(5, m)
            Key.PAGE_DOWN -> tilde(6, m)
            Key.TAB -> if (mods and SHIFT != 0) "\u001b[Z" else alt + "\t"
            Key.ESCAPE -> alt + "\u001b"
            Key.ENTER -> alt + "\r"
            Key.BACKSPACE -> alt + if (mods and CTRL != 0) "\b" else "\u007f"
            Key.F1 -> function('P', m)
            Key.F2 -> function('Q', m)
            Key.F3 -> function('R', m)
            Key.F4 -> function('S', m)
            Key.F5 -> tilde(15, m)
            Key.F6 -> tilde(17, m)
            Key.F7 -> tilde(18, m)
            Key.F8 -> tilde(19, m)
            Key.F9 -> tilde(20, m)
            Key.F10 -> tilde(21, m)
            Key.F11 -> tilde(23, m)
            Key.F12 -> tilde(24, m)
        }
    }

    /** A typed character with Ctrl and Alt applied. */
    fun encode(c: Int, mods: Int): String {
        val code = if (mods and CTRL != 0) control(c) else c
        val s = String(Character.toChars(code))
        return if (mods and ALT != 0) "\u001b" + s else s
    }

    /* As XLookupString() in libX11, which xterm uses, with ? as DEL besides. */
    private fun control(c: Int): Int = when (c) {
        in '@'.code..'~'.code -> c and 0x1f
        ' '.code, '2'.code -> 0
        '3'.code -> 0x1b
        '4'.code -> 0x1c
        '5'.code -> 0x1d
        '6'.code -> 0x1e
        '7'.code, '/'.code -> 0x1f
        '8'.code, '?'.code -> 0x7f
        else -> c
    }

    private fun cursor(c: Char, m: Int, application: Boolean): String = when {
        m != 0 -> "\u001b[1;$m$c"
        application -> "\u001bO$c"
        else -> "\u001b[$c"
    }

    private fun tilde(n: Int, m: Int): String =
        if (m != 0) "\u001b[$n;$m~" else "\u001b[$n~"

    private fun function(c: Char, m: Int): String =
        if (m != 0) "\u001b[1;$m$c" else "\u001bO$c"
}
