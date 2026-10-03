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
        /* Nothing of the stopped upload is left. */
        assertEquals(setOf("big"), sftp.list(dir.path).map { it.name }.toSet())
    }

    @Test
    fun stoppedReplaceKeepsOriginal() {
        File(dir, "f").writeText("original")
        assertFailsWith<SftpCancelledException> {
            sftp.upload(ByteArrayInputStream(ByteArray(2_000_000)), "$dir/f") { it < 100_000 }
        }
        assertEquals("original", File(dir, "f").readText())
        assertEquals(listOf("f"), sftp.list(dir.path).map { it.name })
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
}
