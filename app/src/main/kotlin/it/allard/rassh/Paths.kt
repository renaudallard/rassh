package it.allard.rassh

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Where things live. $HOME is the app files directory. */
class Paths(context: Context) {
    val home: File = context.filesDir
    val sshDir = File(home, ".ssh")
    val config = File(sshDir, "config")
    val agentSocket = File(context.cacheDir, "agent.sock")
    private val bin = File(home, "bin")
    private val lib = context.applicationInfo.nativeLibraryDir
    val ssh = program("ssh")
    val keygen = program("ssh-keygen")
    val agent = program("ssh-agent")
    val add = program("ssh-add")
    val scp = program("scp")
    val sftp = program("sftp")
    val env = listOf(
        "HOME=$home",
        "PATH=$bin:/system/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TMPDIR=${context.cacheDir}",
        "SSH_AUTH_SOCK=$agentSocket",
    )

    private fun program(name: String) = "$lib/lib$name.so"

    fun ensureSshDir() {
        if (!sshDir.isDirectory) sshDir.mkdirs()
        Os.chmod(sshDir.path, "700".toInt(8))
    }

    /*
     * ~/bin holds links named after the programs, ssh finds itself there
     * for ProxyJump, and so do scp, sftp and ProxyCommand lines. The
     * native library directory changes with each install.
     */
    fun linkPrograms() {
        if (!bin.isDirectory) bin.mkdirs()
        for (name in PROGRAMS) {
            val link = File(bin, name)
            link.delete()
            Os.symlink(program(name), link.path)
        }
    }

    /*
     * Replace file with text, readable by the owner only. The data and
     * the rename reach the disk before returning, as callers may delete
     * the only other copy right after.
     */
    @Throws(IOException::class)
    fun writePrivate(file: File, text: String) {
        writePrivate(file, text.toByteArray())
    }

    @Throws(IOException::class)
    fun writePrivate(file: File, data: ByteArray) {
        val tmp = File(file.path + ".tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(data)
                it.fd.sync()
            }
            Os.chmod(tmp.path, "600".toInt(8))
            if (!tmp.renameTo(file)) throw IOException("rename ${tmp.path}")
            val dir = Os.open(file.parent, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(dir)
            } finally {
                Os.close(dir)
            }
        } catch (e: ErrnoException) {
            /* The temporary file may hold a private key. */
            tmp.delete()
            throw e.rethrowAsIOException()
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        private val PROGRAMS = listOf("ssh", "ssh-keygen", "ssh-agent", "ssh-add", "scp", "sftp")
    }
}
