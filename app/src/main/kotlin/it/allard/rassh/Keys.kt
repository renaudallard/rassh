package it.allard.rassh

import java.io.File
import java.io.IOException

/** Private keys, in the vault or in clear in ~/.ssh until moved there. */
object Keys {
    private val RESERVED = setOf("config", "known_hosts", "known_hosts2", "authorized_keys", Vault.FILE)

    @Throws(IOException::class)
    fun list(dir: File, vault: Vault): List<String> = (vault.names() + plaintext(dir)).distinct().sorted()

    /** Private keys in clear in dir. */
    fun plaintext(dir: File): List<String> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && isValidName(it.name) && isPrivateKey(it) }
            .map { it.name }
            .sorted()

    /** A plain file name that does not clash with other ssh files. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && name.none { it == '/' || it.isWhitespace() } &&
            !name.endsWith(".pub") && !name.endsWith(".tmp") && name !in RESERVED

    private fun isPrivateKey(file: File): Boolean =
        try {
            file.bufferedReader().use { it.readLine() }?.startsWith("-----BEGIN ") == true
        } catch (_: IOException) {
            false
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
