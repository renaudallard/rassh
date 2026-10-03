package it.allard.rassh

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The ssh-agent shared by all sessions through SSH_AUTH_SOCK.
 * Used from the main thread.
 */
class Agent(private val paths: Paths) {
    private val handler = Handler(Looper.getMainLooper())
    private var pid = 0

    /* Start the agent and wait for its socket so clients find it. */
    @Throws(IOException::class)
    fun start() {
        if (pid > 0) return
        paths.agentSocket.delete()
        val argv = arrayOf("ssh-agent", "-D", "-a", paths.agentSocket.path)
        val r = Pty.start(paths.agent, argv, paths.env.toTypedArray(), paths.home.path, IntArray(0), ROWS, COLUMNS)
        val pty = ParcelFileDescriptor.adoptFd(r[0])
        val p = r[1]
        pid = p
        thread(name = "agent-read") { drain(pty) }
        thread(name = "agent-wait") {
            try {
                Pty.waitFor(p)
            } catch (_: IOException) {
            }
            handler.post { if (pid == p) pid = 0 }
        }
        for (i in 0 until SOCKET_TRIES) {
            if (paths.agentSocket.exists()) break
            Thread.sleep(SOCKET_DELAY)
        }
    }

    fun stop() {
        if (pid > 0) Pty.sendSignal(pid, SIGTERM)
        pid = 0
    }

    /* The agent prints its socket and pid at start, nothing else matters. */
    private fun drain(pty: ParcelFileDescriptor) {
        val buf = ByteArray(1024)
        pty.use {
            val input = FileInputStream(it.fileDescriptor)
            try {
                while (input.read(buf) >= 0) {
                }
            } catch (_: IOException) {
                /* EIO once the agent exits. */
            }
        }
    }

    companion object {
        private const val ROWS = 24
        private const val COLUMNS = 80
        private const val SIGTERM = 15
        private const val SOCKET_TRIES = 100
        private const val SOCKET_DELAY = 10L
    }
}
