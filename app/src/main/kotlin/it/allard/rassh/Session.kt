package it.allard.rassh

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import it.allard.rassh.sftp.SftpClient
import it.allard.rassh.terminal.Terminal
import it.allard.rassh.terminal.TerminalClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A program running on a pseudo-terminal, feeding a Terminal.
 * Public members are used from the main thread. The terminal is
 * locked while it is fed by the reader thread.
 */
class Session(
    val id: Int,
    val name: String,
    /** The server the session reaches, null for local programs. */
    val server: String?,
    /** The ssh arguments naming the destination, for ssh -G, or null. */
    val target: List<String>?,
    path: String,
    argv: List<String>,
    env: List<String>,
    cwd: String,
    fds: IntArray,
    columns: Int,
    rows: Int,
    private val exitMessage: (Int) -> String,
    private val exited: (Session) -> Unit,
    pipes: Boolean = false,
) : TerminalClient {
    interface Listener {
        fun onUpdate()

        fun onTitleChanged()
    }

    val terminal = Terminal(columns, rows, this)
    var listener: Listener? = null
    var title = name
        private set
    /** When the session started, in milliseconds since the epoch. */
    val opened = System.currentTimeMillis()
    var isRunning = true
        private set

    private val child: Child
    private val pty: ParcelFileDescriptor
    private val output: FileOutputStream

    /* Writes, resizes and the final close are serialized here. */
    private val writer = Executors.newSingleThreadExecutor()
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val updatePending = AtomicBoolean()
    private val pending = AtomicInteger()
    private val readerDone = CountDownLatch(1)

    /*
     * With pipes, the standard input and output of the program, the
     * terminal only showing its messages and taking what it asks for.
     */
    val dataInput: InputStream?
    val dataOutput: OutputStream?

    /**
     * The file browser speaking SFTP over the pipes, kept across its
     * screens. Only one starts it, the version exchange must happen once,
     * the others wait for sftpReady.
     */
    @Volatile
    var sftp: SftpClient? = null
    var sftpStarted = false
    val sftpReady = CountDownLatch(1)

    /* The end of the output, to tell why the program exited. */
    private val tail = ByteArray(TAIL_SIZE)
    private var tailLength = 0

    /** ssh refused to connect as the host key is not the one known. */
    var hostKeyChanged = false
        private set

    /** The user was offered to replace the key, once is enough. */
    var hostKeyOffered = false

    init {
        var toProgram: Array<ParcelFileDescriptor>? = null
        var fromProgram: Array<ParcelFileDescriptor>? = null
        val r = try {
            var stdio = IntArray(0)
            if (pipes) {
                val to = ParcelFileDescriptor.createPipe().also { toProgram = it }
                val from = ParcelFileDescriptor.createPipe().also { fromProgram = it }
                stdio = intArrayOf(to[0].fd, from[1].fd)
            }
            Pty.start(path, cStrings(argv), cStrings(env), cwd, fds, stdio, rows, columns)
        } catch (e: IOException) {
            toProgram?.get(1)?.close()
            fromProgram?.get(0)?.close()
            throw e
        } finally {
            /* The program has its own copies. */
            toProgram?.get(0)?.close()
            fromProgram?.get(1)?.close()
        }
        dataOutput = toProgram?.let { ParcelFileDescriptor.AutoCloseOutputStream(it[1]) }
        dataInput = fromProgram?.let { ParcelFileDescriptor.AutoCloseInputStream(it[0]) }
        pty = ParcelFileDescriptor.adoptFd(r[0])
        child = Child(r[1])
        output = FileOutputStream(pty.fileDescriptor)
        thread(name = "session-$id-read") { read() }
        thread(name = "session-$id-wait") {
            val status = try {
                child.waitFor()
            } catch (_: IOException) {
                -1
            }
            /*
             * Let the reader take what the program wrote last, then end
             * any sequence it left unfinished before writing ours. A
             * process left with the terminal open keeps the reader going.
             */
            readerDone.await(DRAIN_MILLIS, TimeUnit.MILLISECONDS)
            val message = "\r\n" + exitMessage(status) + "\r\n"
            synchronized(terminal) {
                terminal.resetParser()
                terminal.feed(message.toByteArray())
            }
            handler.post { finished() }
        }
    }

    /*
     * A program that stops reading would let the replies to a flood of
     * terminal queries queue up until memory runs out, so writes are
     * dropped beyond MAX_PENDING queued bytes.
     */
    override fun write(data: ByteArray) {
        offer(data)
    }

    /** Queue data for the program, false when dropped, see write(). */
    fun offer(data: ByteArray): Boolean {
        if (pending.addAndGet(data.size) > MAX_PENDING) {
            pending.addAndGet(-data.size)
            return false
        }
        submit {
            try {
                if (!closed) output.write(data)
            } catch (_: IOException) {
            } finally {
                pending.addAndGet(-data.size)
            }
        }
        return true
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

    /*
     * Hang up the program and what it started on the terminal. Whatever
     * still holds the terminal after a while is killed, as it would keep
     * the reader thread and the terminal open. The group is only
     * signalled while the terminal is held, its id cannot be reused then.
     */
    fun close() {
        if (isRunning) child.signal(SIGHUP)
        if (readerDone.count > 0L) child.signalGroup(SIGHUP)
        handler.postDelayed({ if (readerDone.count > 0L) child.signalGroup(SIGKILL) }, CLOSE_MILLIS)
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
                synchronized(terminal) {
                    terminal.feed(buf, 0, n)
                    keepTail(buf, n)
                }
                update()
            }
        } catch (_: IOException) {
            /* EIO once the program and its children closed the terminal. */
        }
        readerDone.countDown()
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

    /* Called with the terminal locked. */
    private fun keepTail(buf: ByteArray, n: Int) {
        val keep = minOf(n, TAIL_SIZE)
        val drop = maxOf(0, tailLength + keep - TAIL_SIZE)
        System.arraycopy(tail, drop, tail, 0, tailLength - drop)
        tailLength -= drop
        System.arraycopy(buf, n - keep, tail, tailLength, keep)
        tailLength += keep
    }

    private fun finished() {
        isRunning = false
        hostKeyChanged = synchronized(terminal) { String(tail, 0, tailLength).contains(HOST_KEY_CHANGED) }
        try {
            dataOutput?.close()
            dataInput?.close()
        } catch (_: IOException) {
        }
        listener?.onUpdate()
        exited(this)
    }

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val TAIL_SIZE = 4096
        /* ssh's warning, before it gives up with "Host key verification failed". */
        private const val HOST_KEY_CHANGED = "REMOTE HOST IDENTIFICATION HAS CHANGED"
        private const val MAX_PENDING = 1 shl 20
        private const val DRAIN_MILLIS = 500L
        private const val CLOSE_MILLIS = 2000L
        private const val SIGHUP = 1
        private const val SIGKILL = 9
    }
}
