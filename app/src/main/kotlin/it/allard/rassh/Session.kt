package it.allard.rassh

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import it.allard.rassh.terminal.Terminal
import it.allard.rassh.terminal.TerminalClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A program running on a pseudo-terminal, feeding a Terminal.
 * Public members are used from the main thread. The terminal is
 * locked while it is fed by the reader thread.
 */
class Session(
    val id: Int,
    val name: String,
    path: String,
    argv: List<String>,
    env: List<String>,
    cwd: String,
    fds: IntArray,
    columns: Int,
    rows: Int,
    private val exitMessage: (Int) -> String,
    private val exited: (Session) -> Unit,
) : TerminalClient {
    interface Listener {
        fun onUpdate()

        fun onTitleChanged()
    }

    val terminal = Terminal(columns, rows, this)
    var listener: Listener? = null
    var title = name
        private set
    var isRunning = true
        private set

    private val pid: Int
    private val pty: ParcelFileDescriptor
    private val output: FileOutputStream

    /* Writes, resizes and the final close are serialized here. */
    private val writer = Executors.newSingleThreadExecutor()
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val updatePending = AtomicBoolean()

    init {
        val r = Pty.start(path, argv.toTypedArray(), env.toTypedArray(), cwd, fds, rows, columns)
        pty = ParcelFileDescriptor.adoptFd(r[0])
        pid = r[1]
        output = FileOutputStream(pty.fileDescriptor)
        thread(name = "session-$id-read") { read() }
        thread(name = "session-$id-wait") {
            val status = try {
                Pty.waitFor(pid)
            } catch (_: IOException) {
                -1
            }
            handler.post { finished(status) }
        }
    }

    override fun write(data: ByteArray) {
        submit {
            try {
                if (!closed) output.write(data)
            } catch (_: IOException) {
            }
        }
    }

    fun resize(columns: Int, rows: Int) {
        synchronized(terminal) {
            if (terminal.columns == columns && terminal.rows == rows) return
            terminal.resize(columns, rows)
        }
        submit {
            try {
                if (!closed) Pty.setWindowSize(pty.fd, rows, columns)
            } catch (_: IOException) {
            }
        }
        update()
    }

    /** Hang up the program, the session ends when it exits. */
    fun hangup() {
        if (isRunning) Pty.sendSignal(pid, SIGHUP)
    }

    override fun titleChanged(title: String) {
        handler.post {
            this.title = title.ifEmpty { name }
            listener?.onTitleChanged()
        }
    }

    private fun read() {
        val input = FileInputStream(pty.fileDescriptor)
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                synchronized(terminal) { terminal.feed(buf, 0, n) }
                update()
            }
        } catch (_: IOException) {
            /* EIO once the program and its children closed the terminal. */
        }
        submit {
            closed = true
            try {
                pty.close()
            } catch (_: IOException) {
            }
        }
        writer.shutdown()
    }

    private fun submit(task: Runnable) {
        try {
            writer.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun update() {
        if (updatePending.compareAndSet(false, true)) {
            handler.post {
                updatePending.set(false)
                listener?.onUpdate()
            }
        }
    }

    private fun finished(status: Int) {
        isRunning = false
        val message = "\r\n" + exitMessage(status) + "\r\n"
        synchronized(terminal) { terminal.feed(message.toByteArray()) }
        listener?.onUpdate()
        exited(this)
    }

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val SIGHUP = 1
    }
}
