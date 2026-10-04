package it.allard.rassh.sftp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * Replies a real server does not easily give, from a scripted one. It
 * answers forever, a client that does not give up hangs: the timeout
 * makes that a failure.
 */
class SftpFakeTest {
    @Test(timeout = TIMEOUT)
    fun listReportsTheServerError() {
        val fake = FakeServer { type, id, _ ->
            when (type) {
                1 -> FakeServer.packet(2) { writeInt(3) }
                11 -> FakeServer.packet(102) { writeInt(id); with(FakeServer) { string("h") } }
                else -> FakeServer.packet(101) {
                    writeInt(id)
                    writeInt(if (type == 12) SftpException.PERMISSION_DENIED else 0)
                    with(FakeServer) {
                        string(if (type == 12) "denied" else "")
                        string("")
                    }
                }
            }
        }
        val client = SftpClient(fake.clientIn, fake.clientOut)
        val e = assertFailsWith<SftpException> { client.list("/d") }
        assertEquals(SftpException.PERMISSION_DENIED, e.status)
        assertEquals("denied", e.message)
    }

    @Test(timeout = TIMEOUT)
    fun emptyReadsEnd() {
        val fake = FakeServer { type, id, _ ->
            when (type) {
                1 -> FakeServer.packet(2) { writeInt(3) }
                3 -> FakeServer.packet(102) { writeInt(id); with(FakeServer) { string("h") } }
                5 -> FakeServer.packet(103) { writeInt(id); writeInt(0) }
                else -> FakeServer.packet(101) {
                    writeInt(id)
                    writeInt(0)
                    with(FakeServer) {
                        string("")
                        string("")
                    }
                }
            }
        }
        val client = SftpClient(fake.clientIn, fake.clientOut)
        val e = assertFailsWith<java.io.IOException> { client.download("/f", { java.io.ByteArrayOutputStream() }) }
        assertEquals("unexpected data", e.message)
    }

    /* A server answering every READDIR with count entries, never with the end. */
    private fun endless(count: Int) = FakeServer { type, id, _ ->
        when (type) {
            1 -> FakeServer.packet(2) { writeInt(3) }
            11 -> FakeServer.packet(102) { writeInt(id); with(FakeServer) { string("h") } }
            12 -> FakeServer.packet(104) {
                writeInt(id)
                writeInt(count)
                repeat(count) {
                    with(FakeServer) {
                        string("f$it")
                        string("")
                    }
                    writeInt(0)
                }
            }
            else -> FakeServer.packet(101) {
                writeInt(id)
                writeInt(0)
                with(FakeServer) {
                    string("")
                    string("")
                }
            }
        }
    }

    @Test(timeout = TIMEOUT)
    fun listEnds() {
        for ((count, error) in listOf(0 to "empty reply", 1000 to "too many files")) {
            val fake = endless(count)
            val client = SftpClient(fake.clientIn, fake.clientOut)
            assertEquals(error, assertFailsWith<java.io.IOException> { client.list("/d") }.message)
        }
    }

    @Test(timeout = TIMEOUT)
    fun fileGrowingAfterTheEnd() {
        var reads = 0
        val fake = FakeServer { type, id, _ ->
            when (type) {
                1 -> FakeServer.packet(2) { writeInt(3) }
                3 -> FakeServer.packet(102) { writeInt(id); with(FakeServer) { string("h") } }
                /* Data, then the end, then data again as the file grows. */
                5 -> if (++reads == 2) FakeServer.packet(101) {
                    writeInt(id)
                    writeInt(SftpException.EOF)
                    with(FakeServer) {
                        string("")
                        string("")
                    }
                } else FakeServer.packet(103) { writeInt(id); writeInt(32768); write(ByteArray(32768)) }
                else -> FakeServer.packet(101) {
                    writeInt(id)
                    writeInt(0)
                    with(FakeServer) {
                        string("")
                        string("")
                    }
                }
            }
        }
        val client = SftpClient(fake.clientIn, fake.clientOut)
        val out = java.io.ByteArrayOutputStream()
        client.download("/f", { out })
        assertEquals(32768, out.size())
    }

    @Test(timeout = TIMEOUT)
    fun cutInAPacket() {
        val toClient = java.io.PipedOutputStream()
        val input = java.io.PipedInputStream(toClient, 1 shl 16)
        val out = java.io.DataOutputStream(toClient)
        /* A VERSION packet announced as 9 bytes, of which 5 come. */
        out.writeInt(9)
        out.write(byteArrayOf(2, 0, 0, 0, 3))
        out.close()
        val e = assertFailsWith<java.io.IOException> { SftpClient(input, java.io.ByteArrayOutputStream()) }
        assertEquals("connection closed", e.message)
    }

    @Test(timeout = TIMEOUT)
    fun shortReadsKeepThePipeline() {
        val size = 100_000
        var reads = 0
        val fake = FakeServer { type, id, packet ->
            when (type) {
                1 -> FakeServer.packet(2) { writeInt(3) }
                3 -> FakeServer.packet(102) { writeInt(id); with(FakeServer) { string("h") } }
                5 -> {
                    reads++
                    /* type, id, handle "h", offset, length */
                    val buf = java.nio.ByteBuffer.wrap(packet, 10, 12)
                    val offset = buf.long
                    val length = buf.int
                    /* A server answering at most 10000 bytes a read. */
                    val n = minOf(length, 10_000, size - offset.toInt())
                    if (n <= 0) FakeServer.packet(101) {
                        writeInt(id)
                        writeInt(SftpException.EOF)
                        with(FakeServer) {
                            string("")
                            string("")
                        }
                    } else FakeServer.packet(103) {
                        writeInt(id)
                        writeInt(n)
                        write(ByteArray(n) { ((offset + it) % 251).toByte() })
                    }
                }
                else -> FakeServer.packet(101) {
                    writeInt(id)
                    writeInt(0)
                    with(FakeServer) {
                        string("")
                        string("")
                    }
                }
            }
        }
        val client = SftpClient(fake.clientIn, fake.clientOut)
        val out = java.io.ByteArrayOutputStream()
        client.download("/f", { out })
        assertContentEquals(ByteArray(size) { (it % 251).toByte() }, out.toByteArray())
        /* Each short read asks the rest only, the requests in flight are not thrown away. */
        assertTrue(reads < 60, "$reads reads")
    }

    @Test(timeout = TIMEOUT)
    fun longPathRefused() {
        val fake = FakeServer { type, id, _ ->
            when (type) {
                1 -> FakeServer.packet(2) { writeInt(3) }
                else -> FakeServer.packet(104) {
                    writeInt(id)
                    writeInt(1)
                    with(FakeServer) {
                        string("/" + "x".repeat(200_000))
                        string("")
                    }
                    writeInt(0)
                }
            }
        }
        val client = SftpClient(fake.clientIn, fake.clientOut)
        assertEquals("name too long", assertFailsWith<java.io.IOException> { client.realpath(".") }.message)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
