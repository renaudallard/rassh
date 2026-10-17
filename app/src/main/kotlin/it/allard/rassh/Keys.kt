package it.allard.rassh

import java.io.File
import java.io.IOException
import java.io.InputStream

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
     * Private keys in clear in dir named in a way isValidName() refuses,
     * made in the local shell or by an old version. They stay out of the
     * vault, and stop exports, until renamed.
     */
    fun misnamed(dir: File): List<String> =
        dir.listFiles().orEmpty()
            .filter {
                it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".pub") &&
                    !isValidName(it.name) && isPrivateKey(it)
            }
            .map { it.name }

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

    /* Only the start is read, any big file may sit in ~/.ssh. */
    private fun isPrivateKey(file: File): Boolean =
        try {
            isPem(file.inputStream().use { it.readNBytes(PEM.size) })
        } catch (_: IOException) {
            false
        }

    /*
     * Up to MAX_SIZE + 1 bytes of a key file, so that a larger one is
     * told apart. readNBytes() leaves the key in buffers of its own, this
     * array is zeroed once copied.
     */
    @Throws(IOException::class)
    fun read(input: InputStream): ByteArray {
        val buf = ByteArray(MAX_SIZE + 1)
        try {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return buf.copyOf(n)
        } finally {
            buf.fill(0)
        }
    }

    /** Whether data starts as a private key file does. */
    fun isPem(data: ByteArray): Boolean = data.size >= PEM.size && PEM.indices.all { data[it] == PEM[it] }

    private val PEM = "-----BEGIN ".toByteArray()

    /**
     * Whether data is a private key in PEM form, followed by nothing but
     * other PEM blocks, a certificate for one, and blank lines: no line of
     * it is one ssh could take for a host key or an option, were it read
     * as known_hosts or as a config. Read in place, data is secret.
     */
    fun isPemKey(data: ByteArray): Boolean {
        var label: String? = null
        var blocks = 0
        var start = 0
        while (start < data.size) {
            var end = start
            while (end < data.size && data[end] != '\n'.code.toByte()) end++
            val stop = if (end > start && data[end - 1] == '\r'.code.toByte()) end - 1 else end
            when {
                label == null && stop == start -> {}
                label == null -> {
                    val begin = String(data, start, stop - start, Charsets.ISO_8859_1)
                    label = BEGIN.matchEntire(begin)?.groupValues?.get(1) ?: return false
                    if (blocks++ == 0 && !label.endsWith("PRIVATE KEY")) return false
                }
                data[start] == '-'.code.toByte() ->
                    if (String(data, start, stop - start, Charsets.ISO_8859_1) == "-----END $label-----") label = null
                    else return false
                !isPemBody(data, start, stop) -> return false
            }
            start = end + 1
        }
        return label == null && blocks > 0
    }

    private val BEGIN = Regex("""-----BEGIN ([A-Z0-9]+(?: [A-Z0-9]+)*)-----""")
    private val HEADERS = listOf("Proc-Type: ", "DEK-Info: ").map { it.toByteArray() }

    /* A base64 line, or a header of an encrypted key in the older form, Proc-Type: 4,ENCRYPTED for one. */
    private fun isPemBody(data: ByteArray, from: Int, to: Int): Boolean {
        val header = HEADERS.find { h -> to - from > h.size && h.indices.all { data[from + it] == h[it] } }
        if (header != null)
            return (from + header.size until to).all { isAlnum(data[it]) || data[it].toInt().toChar() in ",-" }
        var i = from
        while (i < to && (isAlnum(data[i]) || data[i].toInt().toChar() in "+/")) i++
        if (i < to && data[i] == '='.code.toByte()) i++
        if (i < to && data[i] == '='.code.toByte()) i++
        return i == to
    }

    private fun isAlnum(b: Byte): Boolean =
        b.toInt().toChar().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

    /*
     * Remove the private keys left in temporary files, .x.tmp for key x,
     * by a write the app did not finish, see Paths.writePrivate(). Other
     * temporary files hold nothing secret.
     */
    fun removeLeftovers(dir: File) {
        dir.listFiles().orEmpty()
            .filter {
                it.isFile && it.name.startsWith(".") && it.name.endsWith(".tmp") &&
                    isValidName(it.name.removePrefix(".").removeSuffix(".tmp")) && isPrivateKey(it)
            }
            .forEach { it.delete() }
    }

    /*
     * The public key next to a private key, or null. Not one larger than
     * a private key may be: a huge one, made in the local shell, would be
     * more than the clipboard or a share can take, ending the app.
     */
    fun publicKey(dir: File, name: String): String? {
        val f = File(dir, "$name.pub")
        return try {
            if (f.isFile) Paths.bytesOf(f, MAX_SIZE).toString(Charsets.UTF_8).trim().ifEmpty { null } else null
        } catch (_: IOException) {
            null
        }
    }
}
