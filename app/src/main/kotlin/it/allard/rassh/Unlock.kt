package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyPermanentlyInvalidatedException
import it.allard.rassh.config.SshConfig
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.ProviderException

/* The longest keys stay in the agent once loaded, in seconds. */
private const val KEY_LIFETIME = 60

/**
 * Ask for a fingerprint, then call use with the vault key, zeroed
 * afterwards. failed is called when the user cancels or it fails,
 * dismissed when the prompt is closed otherwise, with Back or by the
 * system when the app is left or the screen turns off.
 */
fun Activity.withVaultKey(
    vault: Vault,
    reason: String,
    failed: () -> Unit = {},
    dismissed: () -> Unit = failed,
    use: (ByteArray) -> Unit,
) {
    val cipher = try {
        vault.cipher()
    } catch (_: KeyPermanentlyInvalidatedException) {
        /* Once read: a connection would otherwise open over the dialog without keys. */
        vaultInvalidated(vault, failed)
        return
    } catch (e: GeneralSecurityException) {
        toast(getString(R.string.vault_error, e.message))
        failed()
        return
    } catch (e: IOException) {
        toast(getString(R.string.vault_error, e.message))
        failed()
        return
    } catch (e: ProviderException) {
        /* The Keystore busy or failing, unchecked, would end the app and its sessions. */
        toast(getString(R.string.vault_error, e.message))
        failed()
        return
    }
    /* The cancel button and the error callback may both report the end. */
    var finished = false
    fun end(then: () -> Unit) {
        if (!finished) {
            finished = true
            then()
        }
    }
    fun fail() = end(failed)
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
                } catch (e: ProviderException) {
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
                if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
                    return end(dismissed)
                toast(message.toString())
                fail()
            }
        })
}

/*
 * Call then, unless the vault key was invalidated, which is told first,
 * failed called once that is closed. Other errors are left to
 * withVaultKey().
 */
fun Activity.unlessInvalidated(vault: Vault, failed: () -> Unit, then: () -> Unit) {
    try {
        vault.cipher()
    } catch (_: KeyPermanentlyInvalidatedException) {
        vaultInvalidated(vault, failed)
        return
    } catch (_: GeneralSecurityException) {
    } catch (_: IOException) {
    } catch (_: ProviderException) {
    }
    then()
}

/* closed is called once the dialog is gone. */
private fun Activity.vaultInvalidated(vault: Vault, closed: () -> Unit = {}) {
    val dialog = AlertDialog.Builder(this)
        /* Not telling of keys lost when there were none, as after deleting them all. */
        .setMessage(if (holdsKeys(vault)) R.string.vault_invalidated else R.string.vault_invalidated_empty)
        /* A new vault is set up from the main screen, see MainActivity.setUp(). */
        .setPositiveButton(R.string.vault_reset) { _, _ -> vault.reset() }
        .setNegativeButton(R.string.cancel, null)
        .show()
    /* closed() cleans up what the caller asked for, an export file for one. */
    dismissWithScreen(dialog, closed)
}

/* Whether the vault file holds keys, as it does when it cannot be read. */
private fun holdsKeys(vault: Vault): Boolean = try {
    vault.names().isNotEmpty()
} catch (_: IOException) {
    true
}

/** A strong biometric is enrolled, the vault can be used. */
fun Context.hasStrongBiometric(): Boolean =
    getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * Create the Keystore key, asking first whether a new fingerprint
 * enrollment should destroy it. done is called once it exists.
 */
fun Activity.setUpVault(vault: Vault, done: () -> Unit) {
    if (vault.isSetUp) return done()
    /* A new Keystore key could never read the keys left by the old one. */
    if (vault.exists) return vaultInvalidated(vault)
    if (!hasStrongBiometric()) {
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
        } catch (e: ProviderException) {
            /* The Keystore refusing the key, unchecked, would end the app and its sessions. */
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
 * must be set up, those with a public key next to them and a name the
 * vault does not hold already. done is called when it finished, with
 * false if the fingerprint was refused or the keys could not be moved,
 * dismissed instead when the prompt was closed, see withVaultKey().
 */
fun Activity.protectKeys(paths: Paths, vault: Vault, dismissed: () -> Unit, done: (Boolean) -> Unit) {
    /*
     * Too large a key would fill its pipe to ssh-add before it reads, see
     * Keys.MAX_SIZE. No fingerprint is asked for keys a full vault would
     * refuse anyway.
     */
    val stored = try {
        vault.names().toSet()
    } catch (e: IOException) {
        toast(getString(R.string.vault_error, e.message))
        return done(false)
    }
    /* Not a link: deleting it would leave the key it names in clear. */
    val (taken, fresh) = Keys.plaintext(paths.sshDir).filter {
        val file = File(paths.sshDir, it)
        Keys.publicKey(paths.sshDir, it) != null && file.length() <= Keys.MAX_SIZE &&
            !Files.isSymbolicLink(file.toPath())
    }.partition { it in stored }
    val pending = taken + fresh.take(maxOf(Keys.MAX_COUNT - stored.size, 0))
    if (pending.isEmpty()) return done(true)
    withVaultKey(vault, getString(R.string.protect_reason), { done(false) }, dismissed) { key ->
        /*
         * Named as a stored key, a different one, as copied by scp, must not
         * replace it, the same key is left by a move cut short. The others
         * go into the vault in one write.
         */
        val kept = mutableListOf<String>()
        val moved = mutableListOf<String>()
        val adding = mutableListOf<Pair<String, ByteArray>>()
        /* Read again past the prompt: one grown over Keys.MAX_SIZE meanwhile stays in clear. */
        fun secret(name: String): ByteArray? {
            val data = FileInputStream(File(paths.sshDir, name)).use { Keys.read(it) }
            if (data.size <= Keys.MAX_SIZE) return data
            data.fill(0)
            return null
        }
        try {
            /*
             * ssh-keygen does not sync the files it writes, a power cut
             * could leave a public key empty, which only the key in clear
             * can make again.
             */
            for (name in pending) paths.sync(File(paths.sshDir, "$name.pub"))
            paths.sync(paths.sshDir)
            val known = pending.filter { it in stored }
            val sealed = vault.readAll(key, known)
            try {
                for ((i, name) in known.withIndex()) {
                    val data = secret(name) ?: continue
                    if (sealed[i].contentEquals(data)) moved.add(name) else kept.add(name)
                    data.fill(0)
                }
            } finally {
                sealed.forEach { it.fill(0) }
            }
            for (name in pending.filter { it !in stored })
                secret(name)?.let { adding.add(name to it) }
            vault.addAll(key, adding)
            moved.addAll(adding.map { it.first })
        } catch (e: IOException) {
            /* Like a refusal, not to ask again at once for the same keys. */
            toast(getString(R.string.vault_error, e.message))
            return@withVaultKey done(false)
        } catch (e: GeneralSecurityException) {
            toast(getString(R.string.vault_error, e.message))
            return@withVaultKey done(false)
        } finally {
            adding.forEach { it.second.fill(0) }
        }
        for (name in moved) File(paths.sshDir, name).delete()
        if (kept.isNotEmpty()) {
            toast(getString(R.string.vault_name_taken, kept.joinToString(", ")))
            /* Not asked again for them, as for a refusal. */
            return@withVaultKey done(false)
        }
        done(true)
    }
}

/*
 * Point IdentityFile lines naming the public key of a vault key, as
 * older versions wrote them, back at the key. ssh takes the public key
 * next to a private key that is gone and finds the latter in the agent,
 * and it looks for a certificate as <key>-cert.pub, which the name of the
 * public key hid.
 */
@Throws(IOException::class)
fun useKeyNames(paths: Paths, names: List<String>) {
    /* IdentityFile lines only: x-cert.pub of an old key x-cert is the certificate of x. */
    try {
        rewriteIdentities(paths, names.associate { "$it.pub" to it }, certificates = false)
    } catch (_: Paths.NotUtf8Exception) {
        /* Left as it is, run at each focus, the old names still work. */
    }
}

/** Rewrite IdentityFile lines, and CertificateFile ones with certificates, naming files of ~/.ssh, mapped old to new. */
@Throws(IOException::class)
fun rewriteIdentities(paths: Paths, files: Map<String, String>, certificates: Boolean = true) {
    if (!paths.config.isFile || files.isEmpty()) return
    /* The ways such a file can be written, kept as they are. */
    fun spellings(file: String) = listOf("~/.ssh/$file", "%d/.ssh/$file", File(paths.sshDir, file).path)
    val map = files.flatMap { (from, to) -> spellings(from).zip(spellings(to)) }.toMap()
    val text = paths.textOf(paths.config)
    val changed = SshConfig.replaceIdentities(text, map, certificates)
    if (changed != text) paths.writeText(paths.config, changed)
}

/**
 * Pipes holding the named keys, descriptors 3 and up of the program,
 * which ssh-add opens through links named after the keys, see
 * withAgent(). The write ends are filled and closed, the caller closes
 * the read ends.
 */
@Throws(IOException::class)
fun keyPipes(vault: Vault, dataKey: ByteArray, names: List<String>): List<ParcelFileDescriptor> {
    if (names.size > Keys.MAX_COUNT) throw IOException("more than ${Keys.MAX_COUNT} keys")
    val pipes = mutableListOf<ParcelFileDescriptor>()
    try {
        /* Decrypted first, from one reading of the vault, so that a failure leaves no pipe end open. */
        val secrets = vault.readAll(dataKey, names)
        try {
            for (secret in secrets) {
                val p = ParcelFileDescriptor.createPipe()
                pipes.add(p[0])
                ParcelFileDescriptor.AutoCloseOutputStream(p[1]).use { it.write(secret) }
            }
        } finally {
            secrets.forEach { it.fill(0) }
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

/* Directories naming the keys of a connection for ssh-add, see withAgent(). */
private val KEY_LINKS = Regex("""keys\.\d+""")

/**
 * Run argv with an ssh-agent of its own holding the keys of keyPipes()
 * for KEY_LIFETIME, gone when the program exits, or at a Ctrl-C, which
 * stops it too, the login being over by then. Connections do not
 * share an agent, as the LocalCommand of one emptying it would leave
 * another without keys in the middle of its login. ProxyJump and
 * LocalCommand reach it through SSH_AUTH_SOCK.
 */
fun withAgent(paths: Paths, argv: List<String>, names: List<String>): List<String> {
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    val files = names.indices.joinToString(" ") { "/dev/fd/${3 + it}" }
    val quoted = names.map { quote(it) }
    val links = quoted.withIndex().joinToString("") { (i, name) -> " && ln -s /dev/fd/${3 + i} \"\$d\"/$name" }
    val keys = quoted.joinToString(" ") { "./$it" }
    /*
     * The agent writes its variables to stdout, which the file browser
     * reads, and ssh-add writes there on some errors. The shell cannot
     * close descriptors above 9 and scp and sftp keep them, so what
     * ssh-add left unread in the pipes is drained.
     *
     * ssh-add opens the keys through links named after them, in a
     * directory of the connection, so that a passphrase prompt says
     * which key it is for rather than /dev/fd/3. Given as ./name, as a
     * name may start with - and ssh-add reads - as its standard input.
     *
     * The shell outlives the program here, a Ctrl-C the program handles,
     * as sftp does, must not end the shell with it: a trap catching
     * SIGINT is reset to the default in the program. It is only set for
     * the program, a Ctrl-C at a passphrase of ssh-add stops it all.
     *
     * The keys are open on its descriptors: what it runs before the
     * program comes from /system/bin or the app, never from ~/bin, where
     * the local shell may put anything. The program gets the PATH back,
     * scp and sftp find ssh through it.
     *
     * The agent serves nothing to a connection ssh forwards with agent
     * forwarding, see the patch of ssh-agent.c: the ssh of a jump host
     * reads the config without the -o options of the program, and
     * forwards the agent while the keys are in it when its block asks.
     */
    val script = "p=\$PATH; PATH=/system/bin; " +
        "SSH_AUTH_SOCK=\"\$TMPDIR/agent.\$\$\"; export SSH_AUTH_SOCK; rm -f \"\$SSH_AUTH_SOCK\"; " +
        "${quote(paths.agent)} -D -O no-forwarded -a \"\$SSH_AUTH_SOCK\" </dev/null >/dev/null 2>&1 & agent=\$!; " +
        AGENT_WAIT +
        "d=\"\$TMPDIR/keys.\$\$\"; rm -rf \"\$d\"; mkdir -m 700 \"\$d\"$links && " +
        "(cd \"\$d\" && exec ${quote(paths.add)} -t $KEY_LIFETIME $keys) >&2; rm -rf \"\$d\"; " +
        "cat $files >/dev/null 2>&1; " +
        "PATH=\$p; trap : INT; \"\$0\" \"\$@\"; s=\$?; kill \$agent 2>/dev/null; exit \$s"
    return listOf("sh", "-c", script) + argv
}

/** Empty the agents of the connections, the screen is off. Blocks. */
fun clearAgents(paths: Paths) {
    for (socket in agentSockets(paths)) {
        try {
            runProgram(paths.add, listOf("ssh-add", "-D"), paths.env + "SSH_AUTH_SOCK=$socket", paths.home.path)
        } catch (_: IOException) {
        }
    }
}

/*
 * Remove the key directories of withAgent() left by a shell stopped in
 * ssh-add, at a Close session during a passphrase for one, but not those
 * of a shell still running, named by its pid. They hold links only,
 * removed one by one: following them would reach the descriptors of
 * this process.
 */
fun removeKeyLinks(paths: Paths) {
    for (dir in paths.tmp.listFiles().orEmpty().filter { KEY_LINKS.matches(it.name) }) {
        if (File("/proc/" + dir.name.removePrefix("keys.")).exists()) continue
        dir.listFiles().orEmpty().forEach { it.delete() }
        dir.delete()
    }
}

/** The sockets of the agents of the connections, left over ones included. */
fun agentSockets(paths: Paths): List<File> =
    paths.tmp.listFiles().orEmpty().filter { AGENT_SOCKET.matches(it.name) }

/*
 * ssh options removing the keys from the agent once logged in: ssh runs
 * LocalCommand right after authentication. Options given here win over
 * the config. scp and sftp refuse LocalCommand, KEY_LIFETIME covers them,
 * failed logins and an ssh going through a ControlMaster connection,
 * which does not log in itself.
 */
fun clearAfterLogin(argv: List<String>): List<String> =
    if (argv.firstOrNull() != "ssh") argv
    else listOf("ssh", "-o", "PermitLocalCommand=yes", "-o", "LocalCommand=ssh-add -D >/dev/null 2>&1") + argv.drop(1)
