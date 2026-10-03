package it.allard.rassh.sftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/* Replies a real server does not easily give, from a scripted one. */
class SftpFakeTest {
    @Test
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
}
