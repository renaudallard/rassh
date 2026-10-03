package it.allard.rassh.sftp

import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/* Against OpenSSH's sftp-server, skipped where it is not installed. */
class SftpClientTest {
    private val server = File("/usr/lib/openssh/sftp-server")
    private val dir = File("build/sftp-test").absoluteFile
    private lateinit var process: Process
    private lateinit var sftp: SftpClient

    @BeforeTest
    fun start() {
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
        sftp.download("$dir/d/f", out)
        assertContentEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), sftp.stat("$dir/d/f").size)
    }

    @Test
    fun emptyFile() {
        sftp.upload(ByteArrayInputStream(ByteArray(0)), "$dir/e")
        val out = ByteArrayOutputStream()
        sftp.download("$dir/e", out)
        assertEquals(0, out.size())
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
        assertFailsWith<SftpException> { sftp.download("$dir/missing", ByteArrayOutputStream()) }
        assertFailsWith<SftpException> { sftp.rmdir("$dir/missing") }
        /* The connection is still in step. */
        assertEquals(dir.canonicalPath, sftp.realpath("."))
    }

    @Test
    fun cancel() {
        File(dir, "big").writeBytes(Random(2).nextBytes(2_000_000))
        assertFailsWith<SftpCancelledException> {
            sftp.download("$dir/big", ByteArrayOutputStream()) { it < 100_000 }
        }
        assertFailsWith<SftpCancelledException> {
            sftp.upload(ByteArrayInputStream(ByteArray(2_000_000)), "$dir/up") { it < 100_000 }
        }
        assertEquals(setOf("big", "up"), sftp.list(dir.path).map { it.name }.toSet())
    }
}
