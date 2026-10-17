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
     * parses quotes, so only a safe set of characters is allowed. At most
     * MAX_NAME long: with -cert.pub and the .x.tmp of a write, the files of
     * a key must still fit the 255 bytes of a file name.
     */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_NAME && !name.startsWith(".") && name.all { it in SAFE } &&
            !name.endsWith(".pub") && !name.endsWith(".tmp") && name !in RESERVED

    /** name with the characters isValidName() refuses replaced. */
    fun safeName(name: String): String = name.map { if (it in SAFE) it else '_' }.joinToString("")

    private const val MAX_NAME = 240

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
     * Whether data holds a private key in PEM form, which ssh reads past
     * what lies outside its blocks, see pemOnly().
     */
    fun isPemKey(data: ByteArray): Boolean = pemBlocks(data, null) >= 0

    /**
     * The PEM blocks of data alone, each line without the blanks around
     * it, when one holds a private key: what ssh reads of a key file. No
     * line of it is then one ssh could take for a host key or an option,
     * were it read as known_hosts or as a config. Null when there is no
     * private key block, or a block holds a line that is not base64.
     * Read in place, data is secret, and so is the copy.
     */
    fun pemOnly(data: ByteArray): ByteArray? {
        val out = ByteArray(data.size + 1)
        try {
            val n = pemBlocks(data, out)
            return if (n < 0) null else out.copyOf(n)
        } finally {
            out.fill(0)
        }
    }

    /* The length of the blocks of data written to out, when given, or -1. */
    private fun pemBlocks(data: ByteArray, out: ByteArray?): Int {
        var label: String? = null
        var keys = 0
        var n = 0
        var start = 0
        while (start < data.size) {
            var end = start
            while (end < data.size && data[end] != '\n'.code.toByte()) end++
            var from = start
            var to = end
            while (from < to && isBlank(data[from])) from++
            while (to > from && isBlank(data[to - 1])) to--
            val keep = if (label == null) {
                /* Outside the blocks, a key's public key or openssl's notes for one, left out. */
                label = if (to == from || data[from] != '-'.code.toByte()) null
                    else BEGIN.matchEntire(String(data, from, to - from, Charsets.ISO_8859_1))?.groupValues?.get(1)
                if (label?.endsWith("PRIVATE KEY") == true) keys++
                label != null
            } else if (to > from && data[from] == '-'.code.toByte()) {
                if (String(data, from, to - from, Charsets.ISO_8859_1) != "-----END $label-----") return -1
                label = null
                true
            } else {
                if (!isPemBody(data, from, to)) return -1
                true
            }
            if (keep && out != null) {
                data.copyInto(out, n, from, to)
                n += to - from
                out[n++] = '\n'.code.toByte()
            } else if (keep) {
                n += to - from + 1
            }
            start = end + 1
        }
        return if (label == null && keys > 0) n else -1
    }

    /* Blanks LibreSSL reads past around a line, a carriage return for one. */
    private fun isBlank(b: Byte): Boolean = b.toInt().toChar() in " \t\r\u000c"

    private val BEGIN = Regex("""-----BEGIN ([A-Za-z0-9]+(?: [A-Za-z0-9]+)*)-----""")
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
