package it.allard.rassh.sftp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NameTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun keepsAnyBytes() {
        val names = listOf(
            "plain".toByteArray(),
            "été 😀".toByteArray(),
            bytes(0x63, 0xe9),
            bytes(0xff, 0xfe, 0x80),
            /* U+DC80 in UTF-8, which is not valid. */
            bytes(0xed, 0xb2, 0x80),
            /* A truncated sequence at the end. */
            bytes(0x61, 0xe2, 0x82),
            /* U+10080, whose low surrogate is U+DC80, then a stray byte. */
            "𐂀".toByteArray() + bytes(0x80),
            ByteArray(0),
        )
        for (b in names) assertContentEquals(b, encodeName(decodeName(b)))
    }

    @Test
    fun decodesUtf8() {
        assertEquals("été", decodeName("été".toByteArray()))
        assertEquals("c\udce9", decodeName(bytes(0x63, 0xe9)))
    }
}
