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
    private var child: Child? = null

    /*
     * Start the agent unless it runs. Clients wait for its socket, see
     * AGENT_WAIT. Returns true when a new, empty, agent was started.
     */
    @Throws(IOException::class)
    fun start(): Boolean {
        if (child != null) return false
        paths.agentSocket.delete()
        val argv = arrayOf("ssh-agent", "-D", "-a", paths.agentSocket.path)
        val r = Pty.start(paths.agent, argv, paths.env.toTypedArray(), paths.home.path, IntArray(0), ROWS, COLUMNS)
        val pty = ParcelFileDescriptor.adoptFd(r[0])
        val c = Child(r[1])
        child = c
        thread(name = "agent-read") { drain(pty) }
        thread(name = "agent-wait") {
            try {
                c.waitFor()
            } catch (_: IOException) {
            }
            handler.post { if (child === c) child = null }
        }
        return true
    }

    fun stop() {
        child?.signal(SIGTERM)
        child = null
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
    }
}
