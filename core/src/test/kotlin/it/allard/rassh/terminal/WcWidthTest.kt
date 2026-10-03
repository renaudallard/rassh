package it.allard.rassh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class WcWidthTest {
    @Test
    fun widths() {
        assertEquals(1, WcWidth.width('a'.code))
        assertEquals(0, WcWidth.width(0x7f))
        assertEquals(0, WcWidth.width(0x85))
        assertEquals(1, WcWidth.width(0xe9))
        assertEquals(0, WcWidth.width(0x301))
        assertEquals(0, WcWidth.width(0x200b))
        assertEquals(2, WcWidth.width(0x4e2d))
        assertEquals(2, WcWidth.width(0x1f600))
        assertEquals(2, WcWidth.width(0xff21))
        assertEquals(1, WcWidth.width(0x2500))
        assertEquals(1, WcWidth.width(0x10ffff))
    }
}
