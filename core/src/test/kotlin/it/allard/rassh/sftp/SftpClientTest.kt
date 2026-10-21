package it.allard.rassh.sftp

import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/* Against OpenSSH's sftp-server, skipped where it is not installed, but never on CI. */
class SftpClientTest {
    private val server = File("/usr/lib/openssh/sftp-server")
    private val dir = File("build/sftp-test").absoluteFile
    private lateinit var process: Process
    private lateinit var sftp: SftpClient

    @BeforeTest
    fun start() {
        if (System.getenv("CI") != null) assertTrue(server.canExecute(), "no ${server.path}")
        assumeTrue(server.canExecute())
        dir.deleteRecursively()
        dir.mkdirs()
        process = ProcessBuilder(server.path).directory(dir).start()
        sftp = SftpClient(process.inputStream, process.outputStream)
    }

    @AfterTest
    fun stop() {
        if (!::sftp.isInitialized) return
        sftp.close()
        process.waitFor()
        dir.deleteRecursively()
    }

    @Test
    fun realpath() {
        assertEquals(dir.canonicalPath, sftp.realpath("."))
    }

    @Test
    fun roundTrip() {
        /* Not a multiple of the chunk size, with many requests in flight. */
        val data = Random(1).nextBytes(1_000_003)
        sftp.mkdir("$dir/d")
        var seen = 0L
        sftp.upload(ByteArrayInputStream(data), "$dir/d/f") { seen = it; true }
        assertEquals(data.size.toLong(), seen)
        assertContentEquals(data, File(dir, "d/f").readBytes())
        val out = ByteArrayOutputStream()
        sftp.download("$dir/d/f", { out })
        assertContentEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), sftp.stat("$dir/d/f").size)
    }

    @Test
    fun emptyFile() {
        sftp.upload(ByteArrayInputStream(ByteArray(0)), "$dir/e")
        val out = ByteArrayOutputStream()
        sftp.download("$dir/e", { out })
        assertEquals(0, out.size())
    }

    @Test
    fun namesNotInUtf8() {
        val name = byteArrayOf(0x63, 0xe9.toByte())
        ProcessBuilder("sh", "-c", "printf x > \"$(printf 'c\\351')\"").directory(dir).start().waitFor()
        val entry = sftp.list(dir.path).single()
        assertEquals("c\udce9", entry.name)
        assertContentEquals(name, encodeName(entry.name))
        sftp.rename("$dir/${entry.name}", "$dir/\u00e9t\u00e9")
        assertEquals("\u00e9t\u00e9", sftp.list(dir.path).single().name)
    }

    @Test
    fun cancelledBeforeItBegins() {
        File(dir, "b").writeText("kept")
        assertFailsWith<SftpCancelledException> {
            sftp.upload(ByteArrayInputStream(ByteArray(100_000)), "$dir/b") { false }
        }
        assertEquals("kept", File(dir, "b").readText())
    }

    @Test
    fun listsAndEdits() {
        sftp.mkdir("$dir/sub")
        File(dir, "a").writeText("x")
        val entries = sftp.list(dir.path).associateBy { it.name }
        assertEquals(setOf("sub", "a"), entries.keys)
        assertTrue(entries.getValue("sub").attrs.isDirectory)
        assertEquals(1L, entries.getValue("a").attrs.size)
        sftp.rename("$dir/a", "$dir/b")
        sftp.remove("$dir/b")
        sftp.rmdir("$dir/sub")
        assertEquals(emptyList(), sftp.list(dir.path))
    }

    @Test
    fun manyEntries() {
        /* sftp-server sends a directory in batches of 100. */
        repeat(250) { File(dir, "f$it").writeText("") }
        assertEquals(250, sftp.list(dir.path).size)
    }

    @Test
    fun errors() {
        val e = assertFailsWith<SftpException> { sftp.list("$dir/missing") }
        assertEquals(SftpException.NO_SUCH_FILE, e.status)
        /* Not opened, the local file is left alone when the remote one is missing. */
        var opened = false
        assertFailsWith<SftpException> { sftp.download("$dir/missing", { opened = true; ByteArrayOutputStream() }) }
        assertTrue(!opened)
        assertFailsWith<SftpException> { sftp.rmdir("$dir/missing") }
        /* Refused here: the server would end the connection. */
        val nul = assertFailsWith<IOException> { sftp.lstat("$dir/a\u0000b") }
        assertFalse(nul is SftpException)
        assertTrue(sftp.lstat(dir.path).isDirectory)
        /* The connection is still in step. */
        assertEquals(dir.canonicalPath, sftp.realpath("."))
    }

    @Test
    fun resumeDownload() {
        val data = Random(4).nextBytes(1_000_003)
        File(dir, "src").writeBytes(data)
        val out = ByteArrayOutputStream()
        var last = 0L
        sftp.download("$dir/src", { out }, 400_000) { last = it; true }
        assertContentEquals(data.copyOfRange(400_000, data.size), out.toByteArray())
        assertEquals(data.size.toLong(), last)
    }

    @Test
    fun resumeUpload() {
        val data = Random(5).nextBytes(5_000_001)
        val remote = File(dir, "dst")
        /* What a cut upload may leave: the start, then a hole among the last writes in flight. */
        val cut = 4_000_000
        remote.writeBytes(data.copyOf(cut).also { it.fill(0, cut - 100_000, cut - 50_000) })
        var first = -1L
        val sent = cut + 100_000L
        sftp.resumeUpload(ByteArrayInputStream(data), data.size.toLong(), sent, "$dir/dst", { if (first < 0) first = it; true })
        assertContentEquals(data, remote.readBytes())
        assertEquals(cut - SftpClient.IN_FLIGHT, first)
        /* Longer than what was sent, the upload did not write all of it: written from the start. */
        remote.writeBytes(Random(6).nextBytes(cut + 200_000))
        first = -1
        sftp.resumeUpload(ByteArrayInputStream(data), data.size.toLong(), sent, "$dir/dst", { if (first < 0) first = it; true })
        assertContentEquals(data, remote.readBytes())
        assertEquals(0, first)
        /* Shorter than the writes before those in flight, another file: written from the start. */
        val big = Random(7).nextBytes(9_000_001)
        val bigSent = 8_000_000L
        remote.writeBytes(Random(8).nextBytes(5_000_000))
        first = -1
        sftp.resumeUpload(ByteArrayInputStream(big), big.size.toLong(), bigSent, "$dir/dst", { if (first < 0) first = it; true })
        assertContentEquals(big, remote.readBytes())
        assertEquals(0, first)
        /* Gone, it is made again. */
        remote.delete()
        sftp.resumeUpload(ByteArrayInputStream(data), data.size.toLong(), sent, "$dir/dst")
        assertContentEquals(data, remote.readBytes())
        assertFalse(sftp.isBroken)
    }

    @Test
    fun mayHold() {
        val known = 8_000_000L
        assertTrue(SftpClient.mayHold(known, known))
        assertTrue(SftpClient.mayHold(known - SftpClient.IN_FLIGHT, known))
        assertFalse(SftpClient.mayHold(known + 1, known))
        assertFalse(SftpClient.mayHold(known - SftpClient.IN_FLIGHT - 1, known))
        assertTrue(SftpClient.mayHold(0, 1_000))
        assertFalse(SftpClient.mayHold(0, -1))
    }

    @Test
    fun cancel() {
        File(dir, "big").writeBytes(Random(2).nextBytes(2_000_000))
        assertFailsWith<SftpCancelledException> {
            sftp.download("$dir/big", { ByteArrayOutputStream() }) { it < 100_000 }
        }
        assertFailsWith<SftpCancelledException> {
            sftp.upload(ByteArrayInputStream(ByteArray(2_000_000)), "$dir/up") { it < 100_000 }
        }
        /* Nothing of the stopped upload of a new file is left. */
        assertEquals(setOf("big"), sftp.list(dir.path).map { it.name }.toSet())
    }

    @Test
    fun replaceKeepsPermissions() {
        val f = File(dir, "run.sh")
        f.writeText("old")
        f.setExecutable(true, true)
        val before = sftp.stat(f.path).permissions
        sftp.upload(ByteArrayInputStream("new".toByteArray()), f.path)
        assertEquals("new", f.readText())
        assertEquals(before, sftp.stat(f.path).permissions)
        assertEquals(listOf("run.sh"), sftp.list(dir.path).map { it.name })
    }

    @Test
    fun uploadThroughLink() {
        File(dir, "target").writeText("old")
        java.nio.file.Files.createSymbolicLink(File(dir, "link").toPath(), File(dir, "target").toPath())
        sftp.upload(ByteArrayInputStream("new".toByteArray()), "$dir/link")
        assertEquals("new", File(dir, "target").readText())
        assertTrue(sftp.lstat("$dir/link").isLink)
    }

    @Test
    fun uploadNotReplacing() {
        File(dir, "old").writeText("old")
        java.nio.file.Files.createSymbolicLink(File(dir, "dangling").toPath(), File(dir, "none").toPath())
        for (name in listOf("old", "dangling"))
            assertFailsWith<IOException> { sftp.upload(ByteArrayInputStream("new".toByteArray()), "$dir/$name", false) }
        assertEquals("old", File(dir, "old").readText())
        assertFalse(File(dir, "none").exists())
        sftp.upload(ByteArrayInputStream("new".toByteArray()), "$dir/fresh", false)
        assertEquals("new", File(dir, "fresh").readText())
    }
}
