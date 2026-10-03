package it.allard.rassh.terminal

/**
 * Cell attributes packed in a Long: bits 0-25 hold the foreground color,
 * bits 26-51 the background color and bits 52-63 the flags.
 * A color is an index in the 256 color palette, an RGB value tagged
 * with COLOR_RGB, or COLOR_DEFAULT.
 */
object TextStyle {
    const val COLOR_RGB = 0x1000000
    const val COLOR_DEFAULT = 0x2000000
    private const val COLOR_MASK = 0x3ffffffL

    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val INVISIBLE = 64
    const val STRIKETHROUGH = 128

    val NORMAL = encode(COLOR_DEFAULT, COLOR_DEFAULT, 0)

    fun encode(fg: Int, bg: Int, flags: Int): Long =
        (fg.toLong() and COLOR_MASK) or
            ((bg.toLong() and COLOR_MASK) shl 26) or
            (flags.toLong() shl 52)

    fun fg(style: Long): Int = (style and COLOR_MASK).toInt()

    fun bg(style: Long): Int = ((style ushr 26) and COLOR_MASK).toInt()

    fun flags(style: Long): Int = (style ushr 52).toInt()

    fun rgb(r: Int, g: Int, b: Int): Int =
        COLOR_RGB or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
