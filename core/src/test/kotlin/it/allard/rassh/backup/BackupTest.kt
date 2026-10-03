package it.allard.rassh.backup

import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupTest {
    private val items = listOf(
        Backup.Item(Backup.FILE, "config", "Host pi\n".toByteArray()),
        Backup.Item(Backup.KEY, "id_ed25519", ByteArray(300) { it.toByte() }),
        Backup.Item(Backup.FILE, "empty", ByteArray(0)),
        Backup.Item(Backup.SETTING, "font_size", "14.0".toByteArray()),
    )

    private fun seal(pass: String = "secret") = Backup.seal(pass.toCharArray(), items, 1000)

    @Test
    fun roundTrip() {
        val opened = Backup.open(seal(), "secret".toCharArray())
        assertEquals(items.map { it.type }, opened.map { it.type })
        assertEquals(items.map { it.name }, opened.map { it.name })
        for (i in items.indices) assertContentEquals(items[i].data, opened[i].data)
    }

    @Test
    fun noItems() {
        assertEquals(0, Backup.open(Backup.seal("p".toCharArray(), emptyList(), 1000), "p".toCharArray()).size)
    }

    @Test
    fun wrongPassphrase() {
        assertFailsWith<GeneralSecurityException> { Backup.open(seal(), "Secret".toCharArray()) }
    }

    @Test
    fun tamperedHeader() {
        val sealed = seal()
        sealed[20] = (sealed[20] + 1).toByte()
        assertFailsWith<GeneralSecurityException> { Backup.open(sealed, "secret".toCharArray()) }
    }

    @Test
    fun tamperedData() {
        val sealed = seal()
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertFailsWith<GeneralSecurityException> { Backup.open(sealed, "secret".toCharArray()) }
    }

    @Test
    fun notAnExport() {
        assertFailsWith<IOException> { Backup.open("Host pi\n".toByteArray(), "secret".toCharArray()) }
        assertFailsWith<IOException> { Backup.open(seal().copyOf(40), "secret".toCharArray()) }
    }

    @Test
    fun badIterations() {
        val sealed = seal()
        for (i in 9..12) sealed[i] = 0xff.toByte()
        assertFailsWith<IOException> { Backup.open(sealed, "secret".toCharArray()) }
    }
}
