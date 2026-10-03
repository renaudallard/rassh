package it.allard.rassh

import java.io.IOException

/** Programs running on a pseudo-terminal, see native/pty.c. */
object Pty {
    init {
        System.loadLibrary("rassh")
    }

    /**
     * Start path with argv and envp in cwd, fds become 3, 4, ... in the
     * program. stdio is empty, or the standard input and output, which
     * otherwise are the terminal. Returns the master fd and the pid.
     */
    @JvmStatic
    @Throws(IOException::class)
    external fun start(
        path: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        fds: IntArray,
        stdio: IntArray,
        rows: Int,
        cols: Int,
    ): IntArray

    @JvmStatic
    @Throws(IOException::class)
    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /**
     * Wait for pid to exit without reaping it, returns its status or 128
     * plus the signal number.
     */
    @JvmStatic
    @Throws(IOException::class)
    external fun waitFor(pid: Int): Int

    @JvmStatic
    @Throws(IOException::class)
    external fun reap(pid: Int)

    @JvmStatic
    external fun sendSignal(pid: Int, signal: Int)

    @JvmStatic
    external fun signalGroup(pgid: Int, signal: Int)
}

/*
 * A child process. Its pid is only signalled before it is reaped, as
 * afterwards the kernel may give the pid to another process.
 */
class Child(private val pid: Int) {
    private var reaped = false

    /** Wait for the exit and reap the process, returns its status. */
    @Throws(IOException::class)
    fun waitFor(): Int {
        val status = Pty.waitFor(pid)
        synchronized(this) {
            reaped = true
            Pty.reap(pid)
        }
        return status
    }

    fun signal(signal: Int) {
        synchronized(this) {
            if (!reaped) Pty.sendSignal(pid, signal)
        }
    }

    /*
     * Signal the process group the child leads, its pid stays in use
     * while any member lives, even once the child itself is reaped.
     */
    fun signalGroup(signal: Int) {
        Pty.signalGroup(pid, signal)
    }
}

/** Run a program to completion and return what it printed. */
@Throws(IOException::class)
fun runProgram(path: String, argv: List<String>, env: List<String>, cwd: String): String {
    val r = Pty.start(path, argv.toTypedArray(), env.toTypedArray(), cwd, IntArray(0), IntArray(0), 24, 80)
    val out = java.io.ByteArrayOutputStream()
    android.os.ParcelFileDescriptor.adoptFd(r[0]).use { pty ->
        val input = java.io.FileInputStream(pty.fileDescriptor)
        val buf = ByteArray(4096)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: IOException) {
            /* EIO when the program exits. */
        }
    }
    Child(r[1]).waitFor()
    return out.toString(Charsets.UTF_8).replace("\r\n", "\n").trim()
}
