package it.allard.rassh.sftp

import kotlin.random.Random
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
        val random = Random(1)
        repeat(100_000) {
            val b = random.nextBytes(random.nextInt(9))
            assertContentEquals(b, encodeName(decodeName(b)))
        }
    }

    @Test
    fun showsNoSurrogates() {
        assertEquals("c\ufffd", shownName(decodeName(bytes(0x63, 0xe9))))
        assertEquals("\u00e9t\u00e9 \ud83d\ude00", shownName("\u00e9t\u00e9 \ud83d\ude00"))
    }

    @Test
    fun decodesUtf8() {
        assertEquals("été", decodeName("été".toByteArray()))
        assertEquals("c\udce9", decodeName(bytes(0x63, 0xe9)))
        assertEquals("\udced\udcb2\udc80x", decodeName(bytes(0xed, 0xb2, 0x80, 0x78)))
        assertEquals("a\udce2\udc82", decodeName(bytes(0x61, 0xe2, 0x82)))
        assertEquals("\udcf4\udc90\udc80\udc80", decodeName(bytes(0xf4, 0x90, 0x80, 0x80)))
        assertEquals("\udcc0\udcaf", decodeName(bytes(0xc0, 0xaf)))
        assertEquals("\ud83d\ude00", decodeName(bytes(0xf0, 0x9f, 0x98, 0x80)))
    }
}
