package it.allard.rassh.terminal

import it.allard.rassh.terminal.KeyEncoder.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyEncoderTest {
    @Test
    fun cursorKeys() {
        assertEquals("\u001b[A", KeyEncoder.encode(Key.UP, 0, false))
        assertEquals("\u001bOA", KeyEncoder.encode(Key.UP, 0, true))
        assertEquals("\u001b[1;5D", KeyEncoder.encode(Key.LEFT, KeyEncoder.CTRL, true))
        assertEquals("\u001b[H", KeyEncoder.encode(Key.HOME, 0, false))
        assertEquals("\u001bOF", KeyEncoder.encode(Key.END, 0, true))
    }

    @Test
    fun editingKeys() {
        assertEquals("\u001b[5~", KeyEncoder.encode(Key.PAGE_UP, 0, false))
        assertEquals("\u001b[6;2~", KeyEncoder.encode(Key.PAGE_DOWN, KeyEncoder.SHIFT, false))
        assertEquals("\u001b[3~", KeyEncoder.encode(Key.DELETE, 0, false))
        assertEquals("\t", KeyEncoder.encode(Key.TAB, 0, false))
        assertEquals("\u001b[Z", KeyEncoder.encode(Key.TAB, KeyEncoder.SHIFT, false))
        assertEquals("\u007f", KeyEncoder.encode(Key.BACKSPACE, 0, false))
        assertEquals("\u001b\r", KeyEncoder.encode(Key.ENTER, KeyEncoder.ALT, false))
    }

    @Test
    fun functionKeys() {
        assertEquals("\u001bOP", KeyEncoder.encode(Key.F1, 0, false))
        assertEquals("\u001b[1;3S", KeyEncoder.encode(Key.F4, KeyEncoder.ALT, false))
        assertEquals("\u001b[24~", KeyEncoder.encode(Key.F12, 0, false))
    }

    @Test
    fun characters() {
        assertEquals("\u0003", KeyEncoder.encode('c'.code, KeyEncoder.CTRL))
        assertEquals("\u0003", KeyEncoder.encode('C'.code, KeyEncoder.CTRL))
        assertEquals("\u0000", KeyEncoder.encode(' '.code, KeyEncoder.CTRL))
        assertEquals("\u001b", KeyEncoder.encode('['.code, KeyEncoder.CTRL))
        assertEquals("\u001c", KeyEncoder.encode('|'.code, KeyEncoder.CTRL))
        assertEquals("\u001e", KeyEncoder.encode('~'.code, KeyEncoder.CTRL))
        assertEquals("\u0000", KeyEncoder.encode('`'.code, KeyEncoder.CTRL))
        assertEquals("\u001f", KeyEncoder.encode('/'.code, KeyEncoder.CTRL))
        assertEquals("\u001bx", KeyEncoder.encode('x'.code, KeyEncoder.ALT))
        assertEquals("\u001b\u0018", KeyEncoder.encode('x'.code, KeyEncoder.ALT or KeyEncoder.CTRL))
        assertEquals("|", KeyEncoder.encode('|'.code, 0))
        assertEquals("😀", KeyEncoder.encode(0x1f600, 0))
    }
}
