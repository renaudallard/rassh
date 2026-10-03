package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.system.ErrnoException
import it.allard.rassh.config.SshConfig
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/* How long keys stay in the agent once unlocked, in seconds. */
const val KEY_LIFETIME = 3600

/* Descriptors a program can be given, see MAX_FDS in native/pty.c. */
private const val MAX_KEYS = 64

/**
 * Ask for a fingerprint, then call use with the vault key, zeroed
 * afterwards. failed is called when the user cancels or it fails.
 */
fun Activity.withVaultKey(vault: Vault, reason: String, failed: () -> Unit = {}, use: (ByteArray) -> Unit) {
    val cipher = try {
        vault.cipher()
    } catch (_: KeyPermanentlyInvalidatedException) {
        vaultInvalidated(vault)
        failed()
        return
    } catch (e: GeneralSecurityException) {
        toast(getString(R.string.vault_error, e.message))
        failed()
        return
    } catch (e: IOException) {
        toast(getString(R.string.vault_error, e.message))
        failed()
        return
    }
    /* The cancel button and the error callback may both report the end. */
    var finished = false
    fun fail() {
        if (!finished) {
            finished = true
            failed()
        }
    }
    val prompt = BiometricPrompt.Builder(this)
        .setTitle(getString(R.string.unlock_title))
        .setSubtitle(reason)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButton(getString(R.string.cancel), mainExecutor) { _, _ -> fail() }
        .build()
    prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticated = result.cryptoObject?.cipher ?: return fail()
                val key = try {
                    vault.dataKey(authenticated)
                } catch (e: GeneralSecurityException) {
                    toast(getString(R.string.vault_error, e.message))
                    return fail()
                } catch (e: IOException) {
                    toast(getString(R.string.vault_error, e.message))
                    return fail()
                }
                finished = true
                try {
                    use(key)
                } finally {
                    key.fill(0)
                }
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                    code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
                    toast(message.toString())
                fail()
            }
        })
}

private fun Activity.vaultInvalidated(vault: Vault) {
    AlertDialog.Builder(this)
        .setMessage(R.string.vault_invalidated)
        .setPositiveButton(R.string.vault_reset) { _, _ ->
            vault.reset()
            setUpVault(vault) {}
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/**
 * Create the Keystore key, asking first whether a new fingerprint
 * enrollment should destroy it. done is called once it exists.
 */
fun Activity.setUpVault(vault: Vault, done: () -> Unit) {
    if (vault.isSetUp) return done()
    /* A new Keystore key could never read the keys left by the old one. */
    if (vault.exists) return vaultInvalidated(vault)
    val manager = getSystemService(BiometricManager::class.java)
    if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
        BiometricManager.BIOMETRIC_SUCCESS) {
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_title)
            .setMessage(R.string.vault_no_biometric)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return
    }
    fun create(invalidate: Boolean) {
        try {
            vault.setUp(invalidate)
        } catch (e: GeneralSecurityException) {
            toast(getString(R.string.vault_error, e.message))
            return
        }
        done()
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.vault_title)
        .setMessage(R.string.vault_question)
        .setPositiveButton(R.string.vault_invalidate) { _, _ -> create(true) }
        .setNegativeButton(R.string.vault_keep) { _, _ -> create(false) }
        .setCancelable(false)
        .show()
}

/*
 * Move the private keys left in clear in ~/.ssh into the vault, which
 * must be set up, those with a public key next to them. done is called
 * when it finished, with false if the fingerprint was refused.
 */
fun Activity.protectKeys(paths: Paths, vault: Vault, done: (Boolean) -> Unit) {
    val pending = Keys.plaintext(paths.sshDir).filter { Keys.publicKey(paths.sshDir, it) != null }
    if (pending.isEmpty()) return done(true)
    withVaultKey(vault, getString(R.string.protect_reason), { done(false) }) { key ->
        try {
            for (name in pending) {
                val file = File(paths.sshDir, name)
                val secret = file.readBytes()
                try {
                    vault.add(key, name, secret)
                } finally {
                    secret.fill(0)
                }
                file.delete()
            }
            usePublicKeys(paths, pending)
        } catch (e: IOException) {
            toast(getString(R.string.vault_error, e.message))
        } catch (e: ErrnoException) {
            toast(getString(R.string.vault_error, e.message))
        }
        done(true)
    }
}

/*
 * ssh skips an IdentityFile whose private key is gone but takes a public
 * key and finds the private one in the agent, so point the config at
 * the public keys of the keys moved into the vault.
 */
@Throws(IOException::class)
fun usePublicKeys(paths: Paths, names: List<String>) {
    rewriteIdentities(paths, names.associateWith { "$it.pub" })
}

/** Rewrite IdentityFile lines naming files of ~/.ssh, mapped old to new. */
@Throws(IOException::class)
fun rewriteIdentities(paths: Paths, files: Map<String, String>) {
    if (!paths.config.isFile || files.isEmpty()) return
    /* The ways such a file can be written, kept as they are. */
    fun spellings(file: String) = listOf("~/.ssh/$file", "%d/.ssh/$file", File(paths.sshDir, file).path)
    val map = files.flatMap { (from, to) -> spellings(from).zip(spellings(to)) }.toMap()
    val text = paths.config.readText()
    val changed = SshConfig.replaceIdentities(text, map)
    if (changed != text) paths.writePrivate(paths.config, changed)
}

/**
 * Pipes holding the named keys, to hand to ssh-add as /dev/fd/3 and up.
 * The write ends are filled and closed, the caller closes the read ends.
 */
@Throws(IOException::class)
fun keyPipes(vault: Vault, dataKey: ByteArray, names: List<String>): List<ParcelFileDescriptor> {
    if (names.size > MAX_KEYS) throw IOException("more than $MAX_KEYS keys")
    val pipes = mutableListOf<ParcelFileDescriptor>()
    try {
        for (name in names) {
            val p = ParcelFileDescriptor.createPipe()
            pipes.add(p[0])
            val secret = vault.read(dataKey, name)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(p[1]).use { it.write(secret) }
            } finally {
                secret.fill(0)
            }
        }
    } catch (e: GeneralSecurityException) {
        pipes.forEach { it.close() }
        throw IOException(e)
    } catch (e: IOException) {
        pipes.forEach { it.close() }
        throw e
    }
    return pipes
}

/* Shell code giving the agent 100 short waits to create its socket, it may have just started. */
const val AGENT_WAIT = "i=0; while [ ! -S \"\$SSH_AUTH_SOCK\" ] && [ \$i -lt 100 ]; do sleep 0.01; i=\$((i + 1)); done; "

/** Arguments for SHELL running ssh-add with args once the agent is up. */
fun addCommand(args: List<String>): List<String> =
    listOf("sh", "-c", AGENT_WAIT + "exec ssh-add \"\$@\"", "sh") + args

/*
 * The named vault keys the agent does not hold, asked to the agent
 * itself: ssh-add may have failed, the agent restarted or keys expired.
 * Blocks, not to be called on the main thread.
 */
fun missingKeys(paths: Paths, names: List<String>): List<String> {
    val listed = try {
        runProgram(SHELL, addCommand(listOf("-L")), paths.env, paths.home.path)
    } catch (_: IOException) {
        ""
    }
    val held = listed.lines().mapNotNull { it.split(' ').getOrNull(1) }.toSet()
    return names.filter { Keys.publicKey(paths.sshDir, it)?.split(' ')?.getOrNull(1) !in held }
}

/** ssh-add arguments loading the keys of keyPipes() for KEY_LIFETIME. */
fun addArgs(count: Int): List<String> =
    listOf("-t", KEY_LIFETIME.toString()) + (0 until count).map { "/dev/fd/${3 + it}" }
