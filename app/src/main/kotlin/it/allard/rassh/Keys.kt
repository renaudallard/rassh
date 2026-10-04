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

    /*
     * Whether name is used, by a key in clear or in stored, the names of
     * the vault, or by a file of one, see SUFFIXES. A vault key may have
     * no file left, a lone public key may be all there is.
     */
    fun isTaken(dir: File, name: String, stored: Collection<String>): Boolean =
        name in stored || File(dir, name).exists() || SUFFIXES.any { File(dir, name + it).exists() }

    /*
     * The suffixes of the files of key name among keys. Older versions
     * allowed a key x-cert next to x, x-cert.pub is then its public key.
     */
    fun suffixes(name: String, keys: Collection<String>): List<String> =
        if ("$name-cert" in keys) listOf(".pub") else SUFFIXES

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
     * parses quotes, so only a safe set of characters is allowed.
     */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && name.all { it in SAFE } &&
            !name.endsWith(".pub") && !name.endsWith(".tmp") && name !in RESERVED

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
