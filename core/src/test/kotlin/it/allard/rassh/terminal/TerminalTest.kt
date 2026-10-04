package it.allard.rassh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTest {
    private val replies = StringBuilder()
    private var title = ""

    private val client = object : TerminalClient {
        override fun write(data: ByteArray) {
            replies.append(String(data))
        }

        override fun titleChanged(title: String) {
            this@TerminalTest.title = title
        }
    }

    private fun term(columns: Int = 10, rows: Int = 4, history: Int = 100) =
        Terminal(columns, rows, client, history)

    private fun Terminal.put(s: String) = feed(s.toByteArray())

    private fun Terminal.line(y: Int) = row(y).text(0, columns)

    @Test
    fun printsAndWraps() {
        val t = term()
        t.put("0123456789ab")
        assertEquals("0123456789", t.line(0))
        assertTrue(t.row(0).wrapped)
        assertEquals("ab", t.line(1))
        assertEquals(2, t.cursorX)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun pendingWrapStaysOnLastColumn() {
        val t = term()
        t.put("0123456789")
        assertEquals(9, t.cursorX)
        assertEquals(0, t.cursorY)
        t.put("\r\n")
        assertEquals(1, t.cursorY)
        assertFalse(t.row(0).wrapped)
    }

    @Test
    fun scrollsIntoHistory() {
        val t = term(rows = 2)
        t.put("a\r\nb\r\nc\r\nd")
        assertEquals("c", t.line(0))
        assertEquals("d", t.line(1))
        assertEquals(2, t.historySize)
        assertEquals("a", t.line(-2))
        assertEquals("b", t.line(-1))
        assertEquals("a\nb\nc\nd", t.text(-2, 0, 1, 9))
    }

    @Test
    fun historyIsBounded() {
        val t = term(rows = 2, history = 3)
        repeat(10) { t.put("$it\r\n") }
        assertEquals(3, t.historySize)
        assertEquals(9L, t.historyAdded)
    }

    @Test
    fun cursorPositionAndErase() {
        val t = term()
        t.put("aaaaaaaaaa\r\nbbbbbbbbbb\r\ncccccccccc")
        t.put("\u001b[2;5H")
        assertEquals(4, t.cursorX)
        assertEquals(1, t.cursorY)
        t.put("\u001b[K")
        assertEquals("bbbb", t.line(1))
        t.put("\u001b[1K")
        assertEquals("", t.line(1))
        t.put("\u001b[J")
        assertEquals("", t.line(2))
        assertEquals("aaaaaaaaaa", t.line(0))
        t.put("\u001b[2J")
        assertEquals("", t.line(0))
    }

    @Test
    fun insertAndDeleteCharacters() {
        val t = term()
        t.put("abcdef\r\u001b[2@")
        assertEquals("  abcdef", t.line(0))
        t.put("\u001b[3P")
        assertEquals("bcdef", t.line(0))
        t.put("\u001b[2X")
        assertEquals("  def", t.line(0))
    }

    @Test
    fun insertAndDeleteLinesInRegion() {
        val t = term(rows = 4)
        t.put("1\r\n2\r\n3\r\n4")
        t.put("\u001b[2;3r\u001b[2;1H\u001b[L")
        assertEquals(listOf("1", "", "2", "4"), (0..3).map { t.line(it) })
        t.put("\u001b[M")
        assertEquals(listOf("1", "2", "", "4"), (0..3).map { t.line(it) })
        assertEquals(0, t.historySize)
    }

    @Test
    fun scrollRegionIndex() {
        val t = term(rows = 4)
        t.put("1\r\n2\r\n3\r\n4")
        t.put("\u001b[2;3r\u001b[3;1H\n")
        assertEquals(listOf("1", "3", "", "4"), (0..3).map { t.line(it) })
        t.put("\u001b[2;1H\u001bM")
        assertEquals(listOf("1", "", "3", "4"), (0..3).map { t.line(it) })
    }

    @Test
    fun graphicRendition() {
        val t = term()
        t.put("\u001b[1;31;42ma\u001b[38;5;200;48;2;1;2;3mb\u001b[38:2::4:5:6mc\u001b[0md")
        val s = t.row(0).style
        assertEquals(1, TextStyle.fg(s[0]))
        assertEquals(2, TextStyle.bg(s[0]))
        assertEquals(TextStyle.BOLD, TextStyle.flags(s[0]))
        assertEquals(200, TextStyle.fg(s[1]))
        assertEquals(TextStyle.rgb(1, 2, 3), TextStyle.bg(s[1]))
        assertEquals(TextStyle.rgb(4, 5, 6), TextStyle.fg(s[2]))
        assertEquals(TextStyle.NORMAL, s[3])
    }

    @Test
    fun underlineColorIsSkipped() {
        val t = term()
        t.put("\u001b[31;1mA\u001b[58:2::255:0:0mB\u001b[0m\u001b[58;5;196mC\u001b[73:1:2mD")
        val s = t.row(0).style
        assertEquals(s[0], s[1])
        assertEquals(TextStyle.NORMAL, s[2])
        assertEquals(TextStyle.NORMAL, s[3])
    }

    @Test
    fun wideCharacters() {
        val t = term(columns = 5)
        t.put("中文x")
        assertEquals("中文x", t.line(0))
        assertEquals(TerminalRow.WIDE_TAIL, t.row(0).text[1])
        assertEquals(4, t.cursorX)
        /* Overwriting half of a wide character blanks the other half. */
        t.put("\r\u001b[Cy")
        assertEquals(" y文x", t.line(0))
        /* A wide character does not fit in the last column and wraps. */
        t.put("\r\u001b[4C中")
        assertEquals(" y文", t.line(0))
        assertEquals("中", t.line(1))
    }

    @Test
    fun deleteSplitsWideCharacter() {
        val t = term(columns = 6)
        t.put("a中b\r\u001b[2C\u001b[P")
        assertEquals("a b", t.line(0))
    }

    @Test
    fun utf8AcrossChunks() {
        val t = term()
        val bytes = "é€😀".toByteArray()
        for (b in bytes) t.feed(byteArrayOf(b))
        assertEquals("é€😀", t.line(0))
    }

    @Test
    fun invalidUtf8() {
        val t = term()
        t.feed(byteArrayOf(0xc3.toByte(), 'a'.code.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x80.toByte(), 0x80.toByte()))
        assertEquals("�a��", t.line(0))
    }

    @Test
    fun alternateScreen() {
        val t = term()
        t.put("main\u001b[?1049h")
        assertTrue(t.isAltScreen)
        assertEquals("", t.line(0))
        t.put("alt")
        t.put("\u001b[?1049l")
        assertFalse(t.isAltScreen)
        assertEquals("main", t.line(0))
        assertEquals(4, t.cursorX)
    }

    @Test
    fun replies() {
        val t = term()
        t.put("\u001b[3;4H\u001b[6n\u001b[5n\u001b[c")
        assertEquals("\u001b[3;4R\u001b[0n\u001b[?62;22c", replies.toString())
    }

    @Test
    fun titles() {
        val t = term()
        t.put("\u001b]0;one\u0007")
        assertEquals("one", title)
        t.put("\u001b]2;two\u001b\\x")
        assertEquals("two", title)
        assertEquals("x", t.line(0))
    }

    @Test
    fun ignoredStrings() {
        val t = term()
        t.put("\u001bPqignored\u001b\\a\u001b_apc\u0007b")
        assertEquals("ab", t.line(0))
    }

    @Test
    fun decGraphics() {
        val t = term(columns = 40)
        t.put("\u001b(0" + (0x5f..0x7e).map { it.toChar() }.joinToString("") + "\u001b(Bq")
        val line = t.line(0)
        assertEquals(33, line.length)
        assertEquals('─', line[18])
        assertEquals('│', line[25])
        assertEquals('q', line[32])
    }

    @Test
    fun tabs() {
        val t = term(columns = 20)
        t.put("\tx")
        assertEquals(9, t.cursorX)
        t.put("\u001b[3g\r\u001b[4C\u001bH\r\t")
        assertEquals(4, t.cursorX)
        t.put("\u001b[Z")
        assertEquals(0, t.cursorX)
    }

    @Test
    fun originMode() {
        val t = term(rows = 6)
        t.put("\u001b[2;4r\u001b[?6h\u001b[1;1Hx\u001b[10;1Hy\u001b[6n")
        assertEquals("x", t.line(1))
        assertEquals("y", t.line(3))
        assertEquals("\u001b[3;2R", replies.toString())
    }

    @Test
    fun repeatCharacter() {
        val t = term()
        t.put("a\u001b[3b")
        assertEquals("aaaa", t.line(0))
    }

    @Test
    fun resetParserDropsPartialInput() {
        val t = term()
        t.put("\u001b]0;title without end")
        t.resetParser()
        t.put("x")
        assertEquals("x", t.line(0))
        t.feed(byteArrayOf(0xe2.toByte()))
        t.resetParser()
        t.put("y")
        assertEquals("xy", t.line(0))
    }

    @Test
    fun repeatIsBoundedByTheScreen() {
        val t = term(columns = 4, rows = 2)
        t.put("x\r\n\u001b[1;1Ha\u001b[65535b")
        /* At most 4 x 2 more cells were printed, the rest scrolled away. */
        assertEquals(1, t.historySize)
        assertEquals("aaaa", t.line(0))
    }

    @Test
    fun resizeKeepsCursorLine() {
        val t = term(columns = 10, rows = 4)
        t.put("1\r\n2\r\n3\r\n4")
        t.resize(5, 2)
        assertEquals("3", t.line(0))
        assertEquals("4", t.line(1))
        assertEquals(1, t.cursorY)
        assertEquals("2", t.line(-1))
        t.resize(8, 3)
        assertEquals("", t.line(2))
        assertEquals(8, t.row(0).columns)
    }

    @Test
    fun resizeOnAltScreenKeepsMain() {
        val t = term(columns = 10, rows = 4)
        t.put("1\r\n2\r\n3\r\n4\u001b[?1047h")
        t.resize(10, 2)
        t.put("\u001b[?1047l")
        assertEquals("3", t.line(0))
        assertEquals("4", t.line(1))
        assertEquals("2", t.line(-1))
    }

    @Test
    fun resizeTruncatesWideCharacter() {
        val t = term(columns = 4, rows = 1)
        t.put("ab中")
        t.resize(3, 1)
        assertEquals("ab", t.line(0))
    }

    @Test
    fun saveRestoreCursor() {
        val t = term()
        t.put("\u001b[2;3H\u001b[31m\u001b7\u001b[H\u001b[0m\u001b8x")
        assertEquals(2, t.row(1).text.indexOfFirst { it != 0 })
        assertEquals(1, TextStyle.fg(t.row(1).style[2]))
    }

    @Test
    fun modes() {
        val t = term()
        t.put("\u001b[?1h\u001b[?2004h\u001b[?25l")
        assertTrue(t.applicationCursorKeys)
        assertTrue(t.bracketedPaste)
        assertFalse(t.cursorVisible)
        t.put("\u001bc")
        assertFalse(t.applicationCursorKeys)
        assertFalse(t.bracketedPaste)
        assertTrue(t.cursorVisible)
    }

    @Test
    fun controlsInsideCsi() {
        val t = term()
        t.put("ab\u001b[\r1Cc")
        assertEquals("ac", t.line(0))
        assertEquals(2, t.cursorX)
    }

    @Test
    fun hugeParametersAreClamped() {
        val t = term()
        t.put("\u001b[99999999999999999999;99999999999999999999H")
        assertEquals(9, t.cursorX)
        assertEquals(3, t.cursorY)
    }

    @Test
    fun hugeTabCounts() {
        val t = term(columns = 20)
        t.put("\u001b[65535I")
        assertEquals(19, t.cursorX)
        t.put("\u001b[65535Z")
        assertEquals(0, t.cursorX)
        t.put("\u001b[2I")
        assertEquals(16, t.cursorX)
    }

    @Test
    fun restoresCursorAfterResize() {
        val t = term(columns = 20, rows = 24)
        t.put("\u001b[21;1HS\u001b[1D\u001b7\u001b[24;1H")
        t.resize(20, 12)
        t.put("\u001b8R")
        val lines = (0 until t.rows).map { t.line(it) }
        assertTrue(lines.none { 'S' in it })
        assertTrue(lines.any { it.startsWith("R") })
    }

    @Test
    fun copyKeepsSpacesAtWraps() {
        val t = term(columns = 4, rows = 4)
        t.put("foo bar")
        assertEquals("foo bar", t.text(0, 0, 1, 3))
    }

    @Test
    fun copyAddsNoSpaceBeforeAWrappedWideCharacter() {
        val t = term(columns = 4, rows = 4)
        t.put("abc\u4e2d")
        assertEquals("abc\u4e2d", t.text(0, 0, 1, 3))
    }
}
