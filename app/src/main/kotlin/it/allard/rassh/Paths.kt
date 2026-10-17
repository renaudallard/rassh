package it.allard.rassh

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import it.allard.rassh.config.SshConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files

/** Where things live. $HOME is the app files directory. */
class Paths(context: Context) {
    val home: File = context.filesDir
    val sshDir = File(home, ".ssh")
    val config = File(sshDir, "config")
    /* $TMPDIR, private to the app, the agents of connections put their sockets there. */
    val tmp: File = context.cacheDir
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
        "TMPDIR=$tmp",
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
        /*
         * Replaced by a rename, so that a running ssh always finds them.
         * Links already right stay: a full disk must not stop sessions.
         */
        for (name in PROGRAMS) {
            val link = File(bin, name)
            val current = try {
                Os.readlink(link.path)
            } catch (_: ErrnoException) {
                null
            }
            if (current == program(name)) continue
            val tmp = File(bin, "$name.tmp")
            tmp.delete()
            Os.symlink(program(name), tmp.path)
            Os.rename(tmp.path, link.path)
        }
    }

    /*
     * The text of file, empty when it is missing, for a file the app
     * writes back whole, see utf8().
     */
    @Throws(IOException::class)
    fun textOf(file: File): String {
        checkReachable(file)
        return if (file.isFile) utf8(bytesOf(file), file.name) else ""
    }

    /*
     * A link the app cannot follow, into a storage card removed or no
     * longer allowed for one, is not a missing file: written, the link
     * would become a file of its own, the one it names left behind. One
     * naming a file still to be made in a folder there is taken, as ssh
     * would make it.
     */
    @Throws(IOException::class)
    private fun checkReachable(file: File) {
        val path = file.toPath()
        if (!Files.isSymbolicLink(path) || Files.exists(path)) return
        if (!Files.isDirectory(linkTarget(file).toPath().parent ?: return)) throw UnreachableException(file.name)
    }

    /* The file a link names, followed once: a missing one has no canonical path. */
    private fun linkTarget(file: File): File =
        file.toPath().resolveSibling(Files.readSymbolicLink(file.toPath())).toFile()

    /* The config as shown, decoded leniently: textOf() is for one written back. */
    @Throws(IOException::class)
    fun readConfig(): SshConfig = SshConfig.parse(if (config.isFile) bytesOf(config).toString(Charsets.UTF_8) else "")

    /* Replace a config or known_hosts with text, see checkText(). */
    @Throws(IOException::class)
    fun writeText(file: File, text: String) {
        checkText(file.name, text)
        writePrivate(file, text)
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

    /* Write what is cached of file, or of a directory, to the disk. */
    @Throws(IOException::class)
    fun sync(file: File) {
        try {
            val fd = Os.open(file.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            throw e.rethrowAsIOException()
        }
    }

    @Throws(IOException::class)
    fun writePrivate(file: File, data: ByteArray) {
        checkReachable(file)
        /* Through a link, made in the local shell for instance, the file it names is replaced. */
        val target = when {
            !Files.isSymbolicLink(file.toPath()) -> file
            Files.exists(file.toPath()) -> file.canonicalFile
            else -> linkTarget(file)
        }
        /* Hidden, a key the user named x.tmp is not taken for one, see Keys.removeLeftovers(). */
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(data)
                it.fd.sync()
            }
            Os.chmod(tmp.path, "600".toInt(8))
            if (!tmp.renameTo(target)) throw IOException("rename ${tmp.path}")
            sync(target.parentFile ?: throw IOException("no directory for ${target.path}"))
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

        /*
         * The largest config or known_hosts read, its lines each made a
         * string: 8 MiB of short ones took all the memory of the app.
         */
        const val MAX_TEXT = 1 shl 20

        /* The largest other file of ~/.ssh read whole, a vault or a file to export, as an import takes at most. */
        const val MAX_FILE = 16 shl 20

        /* Refuse text for a config or known_hosts larger than the app reads back, see bytesOf(). */
        @Throws(IOException::class)
        fun checkText(name: String, text: String) {
            if (text.toByteArray().size > MAX_TEXT) throw IOException("$name is too large")
        }

        /*
         * The bytes of file, refused past max: one made huge in the local
         * shell would otherwise take all the memory, and end the app each
         * time it starts.
         */
        @Throws(IOException::class)
        fun bytesOf(file: File, max: Int = MAX_TEXT): ByteArray {
            val data = FileInputStream(file).use { it.readNBytes(max + 1) }
            if (data.size > max) throw IOException("${file.name} is too large")
            return data
        }

        /*
         * data as text, refused when it is not UTF-8: decoded leniently and
         * written back, lines the app never meant to change would.
         */
        @Throws(IOException::class)
        fun utf8(data: ByteArray, name: String): String = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            throw NotUtf8Exception(name)
        }
    }

    /** A file that is a link the app cannot follow, see checkReachable(). */
    class UnreachableException(name: String) : IOException("$name is a link the app cannot follow")

    class NotUtf8Exception(name: String) : IOException("$name is not in UTF-8")
}
