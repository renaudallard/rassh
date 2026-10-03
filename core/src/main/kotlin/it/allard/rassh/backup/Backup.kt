package it.allard.rassh.backup

import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hosts, keys and settings sealed with a passphrase, to move them to
 * another phone. The file is
 *
 *     "RASSHBAK", version (1 byte), iterations (4), salt (16), iv (12)
 *
 * followed by the items, sealed with AES-256-GCM under a key derived
 * with PBKDF2-HMAC-SHA256, the header above as additional data. Each
 * item is a type (1 byte), a name (2 byte length, UTF-8) and data (4
 * byte length). Lengths are big endian. Item data is only held in byte
 * arrays, which the caller zeroes.
 */
object Backup {
    /** A file of ~/.ssh. */
    const val FILE: Byte = 'f'.code.toByte()

    /** A private key. */
    const val KEY: Byte = 'k'.code.toByte()

    /** A setting, its value as text. */
    const val SETTING: Byte = 's'.code.toByte()

    const val ITERATIONS = 600_000

    class Item(val type: Byte, val name: String, val data: ByteArray)

    /** Seal items with passphrase. Slow, not for the main thread. */
    fun seal(passphrase: CharArray, items: List<Item>, iterations: Int = ITERATIONS): ByteArray {
        require(iterations in 1..MAX_ITERATIONS)
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv)
            .array()
        val names = items.map { it.name.toByteArray() }
        names.forEach { require(it.size <= 0xffff) }
        val plain = ByteArray(items.indices.sumOf { 1 + 2 + names[it].size + 4 + items[it].data.size })
        try {
            val b = ByteBuffer.wrap(plain)
            for ((i, item) in items.withIndex()) {
                b.put(item.type).putShort(names[i].size.toShort()).put(names[i])
                b.putInt(item.data.size).put(item.data)
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * The items of a sealed file. Throws GeneralSecurityException for a
     * wrong passphrase or a damaged file, IOException for another file.
     * Slow, not for the main thread.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun open(sealed: ByteArray, passphrase: CharArray): List<Item> {
        if (sealed.size < HEADER_SIZE + TAG_BITS / 8 ||
            !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
            throw IOException("not a rassh export")
        val h = ByteBuffer.wrap(sealed, MAGIC.size, HEADER_SIZE - MAGIC.size)
        if (h.get() != VERSION) throw IOException("unknown export version")
        val iterations = h.getInt()
        if (iterations !in 1..MAX_ITERATIONS) throw IOException("bad iteration count")
        val salt = ByteArray(SALT_SIZE).also { h.get(it) }
        val iv = ByteArray(IV_SIZE).also { h.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(sealed, 0, HEADER_SIZE)
        val plain = cipher.doFinal(sealed, HEADER_SIZE, sealed.size - HEADER_SIZE)
        val items = mutableListOf<Item>()
        try {
            val b = ByteBuffer.wrap(plain)
            while (b.hasRemaining()) {
                val type = b.get()
                val name = ByteArray(b.getShort().toInt() and 0xffff).also { b.get(it) }
                val size = b.getInt()
                if (size < 0 || size > b.remaining()) throw IOException("damaged export")
                items.add(Item(type, String(name), ByteArray(size).also { b.get(it) }))
            }
        } catch (_: BufferUnderflowException) {
            items.forEach { it.data.fill(0) }
            throw IOException("damaged export")
        } catch (e: IOException) {
            items.forEach { it.data.fill(0) }
            throw e
        } finally {
            plain.fill(0)
        }
        return items
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val raw = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            try {
                return SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private val MAGIC = "RASSHBAK".toByteArray()
    private const val VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private val HEADER_SIZE = MAGIC.size + 1 + 4 + SALT_SIZE + IV_SIZE
    private const val MAX_ITERATIONS = 10_000_000
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
}
