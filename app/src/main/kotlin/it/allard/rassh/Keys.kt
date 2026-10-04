package it.allard.rassh

import java.io.File
import java.io.IOException

/** Private keys, in the vault or in clear in ~/.ssh until moved there. */
object Keys {
    /*
     * The largest private key accepted, far above any real one. Keys go
     * to ssh-add through pipes written before it reads, a key must fit
     * in the 64 KiB pipe buffer or the writer blocks.
     */
    const val MAX_SIZE = 65536

    /* The most keys the vault holds, descriptors a program can be given, see MAX_FDS in native/pty.c. */
    const val MAX_COUNT = 64

    private val RESERVED = setOf("config", "known_hosts", "known_hosts2", "authorized_keys", Vault.FILE)

    /** The files named after a key that go with it: its public key and the certificate ssh looks for. */
    val SUFFIXES = listOf(".pub", "-cert.pub")

    @Throws(IOException::class)
    fun list(dir: File, vault: Vault): List<String> = (vault.names() + plaintext(dir)).distinct().sorted()

    /** Private keys in clear in dir. */
    fun plaintext(dir: File): List<String> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && isValidName(it.name) && isPrivateKey(it) }
            .map { it.name }
            .sorted()

    /*
     * A plain file name that does not clash with other ssh files. Names
     * end up in IdentityFile lines, where ssh expands % and ${} and
     * parses quotes, so only a safe set of characters is allowed. The
     * public key of a key named x-cert would be the certificate of x.
     */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && name.all { it in SAFE } &&
            !name.endsWith(".pub") && !name.endsWith(".tmp") && !name.endsWith("-cert") && name !in RESERVED

    /** name with the characters isValidName() refuses replaced. */
    fun safeName(name: String): String = name.map { if (it in SAFE) it else '_' }.joinToString("")

    private val SAFE = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '_', '-')

    private fun isPrivateKey(file: File): Boolean =
        try {
            file.bufferedReader().use { it.readLine() }?.startsWith("-----BEGIN ") == true
        } catch (_: IOException) {
            false
        }

    /*
     * Remove the private keys left in temporary files by a write the app
     * did not finish, see Paths.writePrivate(). Other temporary files
     * hold nothing secret.
     */
    fun removeLeftovers(dir: File) {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".tmp") && isValidName(it.name.removeSuffix(".tmp")) && isPrivateKey(it) }
            .forEach { it.delete() }
    }

    /** The public key next to a private key, or null. */
    fun publicKey(dir: File, name: String): String? {
        val f = File(dir, "$name.pub")
        return try {
            if (f.isFile) f.readText().trim().ifEmpty { null } else null
        } catch (_: IOException) {
            null
        }
    }
}
