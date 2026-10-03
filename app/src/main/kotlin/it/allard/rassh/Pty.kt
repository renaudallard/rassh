package it.allard.rassh

import java.io.IOException

/** Programs running on a pseudo-terminal, see native/pty.c. */
object Pty {
    init {
        System.loadLibrary("rassh")
    }

    /** Start path with argv and envp, returns the master fd and the pid. */
    @JvmStatic
    @Throws(IOException::class)
    external fun start(path: String, argv: Array<String>, envp: Array<String>, rows: Int, cols: Int): IntArray

    @JvmStatic
    @Throws(IOException::class)
    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /** Wait for pid to exit, returns its status or 128 plus the signal number. */
    @JvmStatic
    @Throws(IOException::class)
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun sendSignal(pid: Int, signal: Int)
}

/** Run a program to completion and return what it printed. */
@Throws(IOException::class)
fun runProgram(path: String, argv: List<String>, env: List<String>): String {
    val r = Pty.start(path, argv.toTypedArray(), env.toTypedArray(), 24, 80)
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
    Pty.waitFor(r[1])
    return out.toString(Charsets.UTF_8).replace("\r\n", "\n").trim()
}
