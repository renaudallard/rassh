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
        assertEquals(0, WcWidth.width(0x1160))
        assertEquals(0, WcWidth.width(0xd7b0))
        assertEquals(0, WcWidth.width(0xd7fb))
        assertEquals(2, WcWidth.width(0x4e2d))
        assertEquals(2, WcWidth.width(0x1f600))
        assertEquals(2, WcWidth.width(0xff21))
        assertEquals(1, WcWidth.width(0x2500))
        assertEquals(1, WcWidth.width(0x10ffff))
        /* As glibc counts them. */
        assertEquals(1, WcWidth.width(0x600))
        assertEquals(1, WcWidth.width(0xfffb))
        assertEquals(0, WcWidth.width(0x200d))
        assertEquals(0, WcWidth.width(0x3164))
        assertEquals(0, WcWidth.width(0xffa0))
        assertEquals(2, WcWidth.width(0x3248))
    }
}
