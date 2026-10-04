package it.allard.rassh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Private keys encrypted at rest. Each key is sealed with AES-GCM under
 * a random vault key, its name as additional data. The vault key is
 * itself sealed by a key of the Android Keystore which needs a strong
 * biometric for every use, so reading or adding keys takes one
 * fingerprint. Names are in clear, removing a key needs no fingerprint.
 */
class Vault(private val paths: Paths) {
    class Entry(val name: String, val iv: ByteArray, val data: ByteArray)

    private val file = File(paths.sshDir, FILE)

    /** The Keystore key exists. */
    val isSetUp: Boolean
        get() = keyStore().containsAlias(ALIAS)

    /** Keys were stored, whether or not they can still be read. */
    val exists: Boolean
        get() = file.isFile

    @Throws(IOException::class)
    fun names(): List<String> = load()?.second?.map { it.name } ?: emptyList()

    /**
     * Create the Keystore key. With invalidate, enrolling a new
     * fingerprint destroys it, and with it every stored key.
     */
    fun setUp(invalidate: Boolean) {
        try {
            generate(invalidate, true)
        } catch (_: StrongBoxUnavailableException) {
            generate(invalidate, false)
        }
    }

    private fun generate(invalidate: Boolean, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(invalidate)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(spec)
        generator.generateKey()
    }

    /**
     * Forget the Keystore key and every stored key, with their public
     * keys: left, they would keep the names from new keys and ssh would
     * take them for the private keys, which it cannot load.
     */
    fun reset() {
        val names = try {
            names()
        } catch (_: IOException) {
            emptyList()
        }
        keyStore().deleteEntry(ALIAS)
        file.delete()
        for (name in names)
            if (!File(paths.sshDir, name).exists()) File(paths.sshDir, "$name.pub").delete()
    }

    /**
     * A cipher to authenticate with a biometric prompt, then to pass to
     * dataKey(). Throws KeyPermanentlyInvalidatedException when the
     * Keystore key was invalidated.
     */
    @Throws(IOException::class)
    fun cipher(): Cipher {
        /* A Keystore key gone with the vault still there is as good as invalidated. */
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val sealed = load()?.first
        if (sealed == null)
            cipher.init(Cipher.ENCRYPT_MODE, key)
        else
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.first))
        return cipher
    }

    /**
     * The vault key, unsealed by an authenticated cipher from cipher(),
     * or created and sealed on first use. The caller zeroes it.
     */
    @Throws(IOException::class)
    fun dataKey(cipher: Cipher): ByteArray {
        val v = load()
        if (v != null)
            return cipher.doFinal(v.first.second)
        val key = ByteArray(KEY_BITS / 8)
        SecureRandom().nextBytes(key)
        save(Pair(cipher.iv, cipher.doFinal(key)), emptyList())
        return key
    }

    @Throws(IOException::class)
    fun add(dataKey: ByteArray, name: String, secret: ByteArray) {
        val v = load() ?: throw IOException("no vault")
        val others = v.second.filter { it.name != name }
        /* All are loaded together for a connection, more could not be. */
        if (others.size >= Keys.MAX_COUNT) throw IOException("at most ${Keys.MAX_COUNT} keys")
        save(v.first, others + seal(dataKey, name, secret))
    }

    /**
     * Make keys, by name, the only ones stored, in one write: the limit
     * counts what is kept, and a failure leaves the vault as it was.
     * dataKey may be null when keys is empty.
     */
    @Throws(IOException::class)
    fun replaceAll(dataKey: ByteArray?, keys: List<Pair<String, ByteArray>>) {
        if (keys.size > Keys.MAX_COUNT) throw IOException("at most ${Keys.MAX_COUNT} keys")
        val v = load()
        if (v == null) {
            if (keys.isEmpty()) return
            throw IOException("no vault")
        }
        if (keys.isEmpty()) {
            save(v.first, emptyList())
            return
        }
        val k = dataKey ?: throw IOException("keys locked")
        save(v.first, keys.map { seal(k, it.first, it.second) })
    }

    /* The name is part of the sealed data, so renaming seals the key again. */
    @Throws(IOException::class)
    fun rename(dataKey: ByteArray, from: String, to: String) {
        val v = load() ?: throw IOException("no vault")
        val secret = read(dataKey, from)
        try {
            save(v.first, v.second.filter { it.name != from && it.name != to } + seal(dataKey, to, secret))
        } finally {
            secret.fill(0)
        }
    }

    private fun seal(dataKey: ByteArray, name: String, secret: ByteArray): Entry {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"))
        cipher.updateAAD(name.toByteArray())
        return Entry(name, cipher.iv, cipher.doFinal(secret))
    }

    /** The private key stored as name, the caller zeroes it. */
    @Throws(IOException::class)
    fun read(dataKey: ByteArray, name: String): ByteArray {
        val entry = load()?.second?.find { it.name == name } ?: throw IOException("no key $name")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_BITS, entry.iv))
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(entry.data)
    }

    @Throws(IOException::class)
    fun remove(name: String) {
        val v = load() ?: return
        save(v.first, v.second.filter { it.name != name })
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /* The sealed vault key as iv and ciphertext, and the entries. */
    @Throws(IOException::class)
    private fun load(): Pair<Pair<ByteArray, ByteArray>, List<Entry>>? {
        if (!file.isFile) return null
        try {
            val o = JSONObject(file.readText())
            if (o.getInt("version") != VERSION) throw IOException("unknown vault version")
            val entries = mutableListOf<Entry>()
            val a = o.getJSONArray("entries")
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                entries.add(Entry(e.getString("name"), decode(e.getString("iv")), decode(e.getString("data"))))
            }
            return Pair(Pair(decode(o.getString("iv")), decode(o.getString("key"))), entries)
        } catch (e: JSONException) {
            throw IOException("corrupt vault", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("corrupt vault", e)
        }
    }

    @Throws(IOException::class)
    private fun save(sealed: Pair<ByteArray, ByteArray>, entries: List<Entry>) {
        val a = JSONArray()
        for (e in entries)
            a.put(JSONObject().put("name", e.name).put("iv", encode(e.iv)).put("data", encode(e.data)))
        val o = JSONObject()
            .put("version", VERSION)
            .put("iv", encode(sealed.first))
            .put("key", encode(sealed.second))
            .put("entries", a)
        paths.writePrivate(file, o.toString())
    }

    private fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val FILE = "keys.vault"
        private const val ALIAS = "rassh-vault"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val VERSION = 1
    }
}
