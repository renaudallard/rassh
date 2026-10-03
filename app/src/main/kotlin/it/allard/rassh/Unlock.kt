package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyPermanentlyInvalidatedException
import it.allard.rassh.config.SshConfig
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/* The longest keys stay in the agent once loaded, in seconds. */
private const val KEY_LIFETIME = 60


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
 * when it finished, with false if the fingerprint was refused or the
 * keys could not be moved.
 */
fun Activity.protectKeys(paths: Paths, vault: Vault, done: (Boolean) -> Unit) {
    /*
     * Too large a key would fill its pipe to ssh-add before it reads, see
     * Keys.MAX_SIZE. No fingerprint is asked for keys a full vault would
     * refuse anyway.
     */
    val room = try {
        Keys.MAX_COUNT - vault.names().size
    } catch (e: IOException) {
        toast(getString(R.string.vault_error, e.message))
        return done(false)
    }
    val pending = Keys.plaintext(paths.sshDir).filter {
        Keys.publicKey(paths.sshDir, it) != null && File(paths.sshDir, it).length() <= Keys.MAX_SIZE
    }.take(maxOf(room, 0))
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
            /* Like a refusal, not to ask again at once for the same keys. */
            toast(getString(R.string.vault_error, e.message))
            return@withVaultKey done(false)
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
    if (names.size > Keys.MAX_COUNT) throw IOException("more than ${Keys.MAX_COUNT} keys")
    val pipes = mutableListOf<ParcelFileDescriptor>()
    try {
        for (name in names) {
            /* Decrypted first, so that a failure leaves no pipe end open. */
            val secret = vault.read(dataKey, name)
            try {
                val p = ParcelFileDescriptor.createPipe()
                pipes.add(p[0])
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

/* Shell code giving the agent 100 short waits to create its socket, it has just started. */
private const val AGENT_WAIT = "i=0; while [ ! -S \"\$SSH_AUTH_SOCK\" ] && [ \$i -lt 100 ]; do sleep 0.01; i=\$((i + 1)); done; "

/* Agents of a connection, see withAgent(), named by the pid of their shell. */
private val AGENT_SOCKET = Regex("""agent\.\d+""")

/**
 * Run argv with an ssh-agent of its own holding the keys of keyPipes()
 * for KEY_LIFETIME, gone when the program exits. Connections do not
 * share an agent, as the LocalCommand of one emptying it would leave
 * another without keys in the middle of its login. ProxyJump and
 * LocalCommand reach it through SSH_AUTH_SOCK.
 */
fun withAgent(argv: List<String>, keys: Int): List<String> {
    val files = (0 until keys).joinToString(" ") { "/dev/fd/${3 + it}" }
    /*
     * The agent writes its variables to stdout, which the file browser
     * reads, and ssh-add writes there on some errors. The shell cannot
     * close descriptors above 9 and scp and sftp keep them, so what
     * ssh-add left unread in the pipes is drained.
     */
    /*
     * The shell outlives the program here, a Ctrl-C the program handles,
     * as sftp does, must not end the shell with it: a trap catching
     * SIGINT is reset to the default in the program.
     */
    val script = "trap : INT; " +
        "SSH_AUTH_SOCK=\"\$TMPDIR/agent.\$\$\"; export SSH_AUTH_SOCK; rm -f \"\$SSH_AUTH_SOCK\"; " +
        "ssh-agent -D -a \"\$SSH_AUTH_SOCK\" </dev/null >/dev/null 2>&1 & agent=\$!; " +
        AGENT_WAIT +
        "ssh-add -t $KEY_LIFETIME $files >&2; cat $files >/dev/null 2>&1; " +
        "\"\$0\" \"\$@\"; s=\$?; kill \$agent 2>/dev/null; exit \$s"
    return listOf("sh", "-c", script) + argv
}

/** Empty the agents of the connections, the screen is off. Blocks. */
fun clearAgents(paths: Paths) {
    for (socket in agentSockets(paths)) {
        try {
            runProgram(SHELL, listOf("sh", "-c", "exec ssh-add -D"), paths.env + "SSH_AUTH_SOCK=$socket", paths.home.path)
        } catch (_: IOException) {
        }
    }
}

/** The sockets of the agents of the connections, left over ones included. */
fun agentSockets(paths: Paths): List<File> =
    paths.tmp.listFiles().orEmpty().filter { AGENT_SOCKET.matches(it.name) }

/*
 * ssh options removing the keys from the agent once logged in: ssh runs
 * LocalCommand right after authentication. Options given here win over
 * the config. scp and sftp refuse LocalCommand, KEY_LIFETIME covers them
 * and failed logins.
 */
fun clearAfterLogin(argv: List<String>): List<String> =
    if (argv.firstOrNull() != "ssh") argv
    else listOf("ssh", "-o", "PermitLocalCommand=yes", "-o", "LocalCommand=ssh-add -D >/dev/null 2>&1") + argv.drop(1)
