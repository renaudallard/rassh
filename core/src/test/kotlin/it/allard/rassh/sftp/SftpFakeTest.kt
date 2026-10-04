package it.allard.rassh.sftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * Replies a real server does not easily give, from a scripted one. It
 * answers forever, a client that does not give up hangs: the timeout
 * makes that a failure.
 */
class SftpFakeTest {
    @Test(timeout = TIMEOUT)
    fun listReportsTheServerError() {
        val fake = FakeServer { type, id ->
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
        val fake = FakeServer { type, id ->
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
        val e = assertFailsWith<java.io.IOException> { client.download("/f", java.io.ByteArrayOutputStream()) }
        assertEquals("unexpected data", e.message)
    }

    /* A server answering every READDIR with count entries, never with the end. */
    private fun endless(count: Int) = FakeServer { type, id ->
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
    fun longPathRefused() {
        val fake = FakeServer { type, id ->
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
