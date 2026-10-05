package it.allard.rassh.sftp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/*
 * An SFTP server answering each request with what reply returns for its
 * type, id and whole packet, to test replies a real server does not
 * easily give.
 */
class FakeServer(private val reply: (type: Int, id: Int, packet: ByteArray) -> ByteArray) {
    private val toServer = PipedInputStream(1 shl 20)
    private val fromServer = PipedInputStream(1 shl 20)
    val clientOut = PipedOutputStream(toServer)
    val clientIn = fromServer
    private val serverOut = DataOutputStream(PipedOutputStream(fromServer))

    init {
        thread(isDaemon = true) {
            val input = DataInputStream(toServer)
            try {
                while (true) {
                    val packet = ByteArray(input.readInt())
                    input.readFully(packet)
                    val type = packet[0].toInt() and 0xff
                    val id = if (type == 1) 0 else
                        (packet[1].toInt() and 0xff shl 24) or (packet[2].toInt() and 0xff shl 16) or
                            (packet[3].toInt() and 0xff shl 8) or (packet[4].toInt() and 0xff)
                    val body = reply(type, id, packet)
                    serverOut.writeInt(body.size)
                    serverOut.write(body)
                    serverOut.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun packet(type: Int, build: DataOutputStream.() -> Unit): ByteArray {
            val b = ByteArrayOutputStream()
            val d = DataOutputStream(b)
            d.writeByte(type)
            d.build()
            return b.toByteArray()
        }

        fun DataOutputStream.string(s: String) {
            val bytes = s.toByteArray()
            writeInt(bytes.size)
            write(bytes)
        }
    }
}
