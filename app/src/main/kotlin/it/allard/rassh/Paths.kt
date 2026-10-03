package it.allard.rassh

import android.content.Context
import android.system.Os
import java.io.File

/** Where things live. $HOME is the app files directory. */
class Paths(context: Context) {
    val home: File = context.filesDir
    val sshDir = File(home, ".ssh")
    val config = File(sshDir, "config")
    private val bin = context.applicationInfo.nativeLibraryDir
    val ssh = "$bin/libssh.so"
    val keygen = "$bin/libssh-keygen.so"
    val env = listOf(
        "HOME=$home",
        "PATH=/system/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TMPDIR=${context.cacheDir}",
    )

    fun ensureSshDir() {
        if (!sshDir.isDirectory) sshDir.mkdirs()
        Os.chmod(sshDir.path, "700".toInt(8))
    }

    /** Replace file with text, readable by the owner only. */
    fun writePrivate(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        Os.chmod(tmp.path, "600".toInt(8))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("rename ${tmp.path}")
        }
    }
}
