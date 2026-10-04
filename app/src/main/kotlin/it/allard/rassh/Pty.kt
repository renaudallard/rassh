package it.allard.rassh

import java.io.IOException

/** Programs running on a pseudo-terminal, see native/pty.c. */
object Pty {
    init {
        System.loadLibrary("rassh")
    }

    /**
     * Start path with argv and envp, from cStrings(), in cwd, fds become
     * 3, 4, ... in the program. stdio is empty, or the standard input and
     * output, which otherwise are the terminal. Returns the master fd and
     * the pid.
     */
    @JvmStatic
    @Throws(IOException::class)
    external fun start(
        path: String,
        argv: Array<ByteArray>,
        envp: Array<ByteArray>,
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
 * A child process. Its pid, and the group it leads, are only signalled
 * before it is reaped, as afterwards the kernel may give the pid to
 * another process. Until then, exited, it keeps the pid as a zombie.
 */
class Child(private val pid: Int) {
    private var reaped = false

    /** Wait for the exit, returns the status. The pid stays taken until reap(). */
    @Throws(IOException::class)
    fun waitFor(): Int = Pty.waitFor(pid)

    /** Free the pid of the child, which must have exited. */
    @Throws(IOException::class)
    fun reap() {
        synchronized(this) {
            if (reaped) return
            reaped = true
            Pty.reap(pid)
        }
    }

    fun signal(signal: Int) {
        synchronized(this) {
            if (!reaped) Pty.sendSignal(pid, signal)
        }
    }

    /*
     * Signal the process group the child leads. A member may have left
     * it for a session of its own, as a daemon does, the group then
     * holds the child alone and only its zombie keeps the id.
     */
    fun signalGroup(signal: Int) {
        synchronized(this) {
            if (!reaped) Pty.signalGroup(pid, signal)
        }
    }
}

/** Run a program to completion and return what it printed. */
@Throws(IOException::class)
fun runProgram(path: String, argv: List<String>, env: List<String>, cwd: String): String =
    execute(path, argv, env, cwd).first

/** Run a program to completion and return what it printed and its status. */
@Throws(IOException::class)
fun execute(path: String, argv: List<String>, env: List<String>, cwd: String): Pair<String, Int> {
    val r = Pty.start(path, cStrings(argv), cStrings(env), cwd, IntArray(0), IntArray(0), 24, 80)
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
    val child = Child(r[1])
    val status = child.waitFor()
    child.reap()
    return Pair(out.toString(Charsets.UTF_8).replace("\r\n", "\n").trim(), status)
}

/* Strings for C, in UTF-8. A NUL would end one early without a word. */
@Throws(IOException::class)
fun cStrings(list: List<String>): Array<ByteArray> = Array(list.size) {
    if ('\u0000' in list[it]) throw IOException("NUL in argument")
    list[it].toByteArray(Charsets.UTF_8)
}
