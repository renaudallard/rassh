package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.text.InputType
import android.text.TextUtils
import android.widget.EditText
import it.allard.rassh.backup.Backup
import it.allard.rassh.config.SshConfig
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.concurrent.thread

/* Moving hosts, keys and settings to another phone, see Backup. */

/* Far above what Keys.MAX_COUNT keys and a config take. */
private const val MAX_EXPORT_SIZE = 16 * 1024 * 1024
private const val MIN_PASSPHRASE = 8
private const val CONFIG = "config"
private const val KNOWN_HOSTS = "known_hosts"

/**
 * Seal the hosts, keys and settings into uri with a passphrase. The file
 * picker created uri, it is deleted when the export does not happen.
 */
fun Activity.exportTo(uri: Uri, paths: Paths, vault: Vault) {
    fun discard() {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
            /* Not every provider deletes, the file is then left empty. */
        }
    }
    askPassphrase(R.string.export, true, { discard() }) { pass ->
        fun export(key: ByteArray?) {
            val items = try {
                exportItems(paths, vault, key)
            } catch (e: IOException) {
                pass.fill('\u0000')
                discard()
                toast(getString(R.string.export_failed, e.message))
                return
            }
            thread(name = "export") {
                val error = try {
                    val sealed = Backup.seal(pass, items)
                    val out = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")
                    out.use { it.write(sealed) }
                    null
                } catch (e: Exception) {
                    if (!isFileError(e)) throw e
                    discard()
                    e.message
                } finally {
                    items.forEach { it.data.fill(0) }
                    pass.fill('\u0000')
                }
                runOnUiThread {
                    toast(if (error == null) getString(R.string.exported) else getString(R.string.export_failed, error))
                }
            }
        }
        val names = try {
            vault.names()
        } catch (e: IOException) {
            pass.fill('\u0000')
            discard()
            toast(getString(R.string.vault_error, e.message))
            return@askPassphrase
        }
        if (names.isEmpty())
            export(null)
        else
            withVaultKey(vault, getString(R.string.export_reason), {
                pass.fill('\u0000')
                discard()
            }) { key -> export(key) }
    }
}

/* key unseals the vault keys, null when there are none. */
@Throws(IOException::class)
private fun Context.exportItems(paths: Paths, vault: Vault, key: ByteArray?): List<Backup.Item> {
    val items = mutableListOf<Backup.Item>()
    try {
        for (name in listOf(CONFIG, KNOWN_HOSTS)) {
            val f = File(paths.sshDir, name)
            if (f.isFile) items.add(Backup.Item(Backup.FILE, name, f.readBytes()))
        }
        val sealed = vault.names()
        for (name in Keys.list(paths.sshDir, vault)) {
            val secret = if (name in sealed)
                vault.read(key ?: throw IOException("keys locked"), name)
            else
                File(paths.sshDir, name).readBytes()
            items.add(Backup.Item(Backup.KEY, name, secret))
            for (suffix in Keys.SUFFIXES) {
                val file = File(paths.sshDir, name + suffix)
                if (file.isFile) items.add(Backup.Item(Backup.FILE, file.name, file.readBytes()))
            }
        }
        val prefs = getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(TerminalView.PREF_FONT_SIZE)) {
            val size = prefs.getFloat(TerminalView.PREF_FONT_SIZE, 0f).toString()
            items.add(Backup.Item(Backup.SETTING, TerminalView.PREF_FONT_SIZE, size.toByteArray()))
        }
    } catch (e: GeneralSecurityException) {
        items.forEach { it.data.fill(0) }
        throw IOException(e)
    } catch (e: IOException) {
        items.forEach { it.data.fill(0) }
        throw e
    }
    return items
}

/**
 * Read an export from uri and add the hosts and keys whose names are not
 * used yet, or replace them all, as the user chooses. done is called
 * once something changed.
 */
fun Activity.importFrom(uri: Uri, paths: Paths, vault: Vault, done: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(R.string.import_)
        .setMessage(R.string.import_question)
        .setPositiveButton(R.string.import_append) { _, _ -> readExport(uri, paths, vault, false, done) }
        .setNeutralButton(R.string.import_replace) { _, _ -> readExport(uri, paths, vault, true, done) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

private fun Activity.readExport(uri: Uri, paths: Paths, vault: Vault, replace: Boolean, done: () -> Unit) {
    askPassphrase(R.string.import_, false) { pass ->
        /* In the thread, a provider may have to download the file first. */
        thread(name = "import") {
            var items: List<Backup.Item>? = null
            val error = try {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
                val sealed = input.use { it.readNBytes(MAX_EXPORT_SIZE + 1) }
                if (sealed.size > MAX_EXPORT_SIZE) throw IOException("file too large")
                items = checked(Backup.open(sealed, pass))
                null
            } catch (_: GeneralSecurityException) {
                getString(R.string.wrong_passphrase)
            } catch (e: Exception) {
                if (!isFileError(e)) throw e
                e.message
            } finally {
                pass.fill('\u0000')
            }
            runOnUiThread {
                val opened = items
                if (opened == null) {
                    toast(getString(R.string.restore_failed, error))
                } else if (isDestroyed) {
                    /* Adding keys needs a fingerprint prompt, which only a live screen shows. */
                    opened.forEach { it.data.fill(0) }
                    applicationContext.toast(getString(R.string.restore_failed, getString(R.string.import_interrupted)))
                } else {
                    store(paths, vault, opened, replace, done)
                }
            }
        }
    }
}

/* items when every name is one an export holds, zeroed otherwise. */
@Throws(IOException::class)
private fun checked(items: List<Backup.Item>): List<Backup.Item> {
    fun valid(item: Backup.Item): Boolean = when (item.type) {
        Backup.FILE -> item.name == CONFIG || item.name == KNOWN_HOSTS ||
            Keys.SUFFIXES.any { item.name.endsWith(it) && Keys.isValidName(item.name.removeSuffix(it)) }
        Backup.KEY -> Keys.isValidName(item.name)
        /* Settings of later versions are skipped. */
        Backup.SETTING -> true
        else -> false
    }
    if (!items.all { valid(it) } || items.map { Pair(it.type, it.name) }.distinct().size != items.size) {
        items.forEach { it.data.fill(0) }
        throw IOException("damaged export")
    }
    return items
}

/* Store the items, which are zeroed afterwards, asking for a fingerprint when keys are to be added. */
private fun Activity.store(paths: Paths, vault: Vault, items: List<Backup.Item>, replace: Boolean, done: () -> Unit) {
    fun zero() = items.forEach { it.data.fill(0) }
    val present = try {
        Keys.list(paths.sshDir, vault)
    } catch (e: IOException) {
        zero()
        toast(getString(R.string.vault_error, e.message))
        return
    }
    fun write(key: ByteArray?) {
        try {
            val (hosts, keys) = if (replace) replaceAll(paths, vault, key, items, present)
                else append(paths, vault, key, items, present)
            toast(getString(R.string.imported, hosts, keys))
        } catch (e: IOException) {
            toast(getString(R.string.restore_failed, e.message))
        } catch (e: ErrnoException) {
            toast(getString(R.string.restore_failed, e.message))
        } finally {
            zero()
        }
        done()
    }
    if (items.none { it.type == Backup.KEY && (replace || it.name !in present) && sealable(items, it) })
        return write(null)
    if (!vault.isSetUp) {
        zero()
        toast(getString(R.string.restore_failed, getString(R.string.restore_no_vault)))
        return
    }
    withVaultKey(vault, getString(R.string.import_reason), { zero() }) { key -> write(key) }
}

/* Returns the number of hosts and keys there are now. */
@Throws(IOException::class)
private fun Context.replaceAll(paths: Paths, vault: Vault, key: ByteArray?, items: List<Backup.Item>,
    present: List<String>): Pair<Int, Int> {
    val dir = paths.sshDir
    paths.ensureSshDir()
    /*
     * Write the export first and only then remove what it does not hold,
     * so that a failure leaves the old setup rather than nothing. The
     * vault is replaced in one write, and the key files only follow once
     * it succeeded: an old vault key must not end up paired with the
     * public key or the clear copy of another.
     */
    val keys = items.filter { it.type == Backup.KEY }
    val sealed = keys.filter { sealable(items, it) }.map { it.name }.toSet()
    /* Checked first, not to stop with only part of the file written. */
    if (sealed.size > Keys.MAX_COUNT) throw IOException("at most ${Keys.MAX_COUNT} keys")
    val publicKeys = items.filter { it.type == Backup.FILE && it.name.endsWith(".pub") }
    for (item in items) {
        when (item.type) {
            Backup.FILE -> if (item !in publicKeys) paths.writePrivate(File(dir, item.name), item.data)
            Backup.SETTING -> putSetting(item)
        }
    }
    vault.replaceAll(key, keys.filter { it.name in sealed }.map { it.name to it.data })
    for (item in publicKeys) paths.writePrivate(File(dir, item.name), item.data)
    useKeyNames(paths, sealed.toList())
    val clear = keys.map { it.name }.toSet() - sealed
    for (item in keys) {
        dropStrayFiles(paths, items, item.name)
        if (item.name !in sealed) paths.writePrivate(File(dir, item.name), item.data)
    }
    val files = items.filter { it.type == Backup.FILE }.map { it.name }.toSet()
    for (name in present) {
        /* The imported copy replaces the one of the same name. */
        if (name in sealed) {
            File(dir, name).delete()
        } else if (name !in clear) {
            File(dir, name).delete()
            for (suffix in Keys.SUFFIXES) File(dir, name + suffix).delete()
        }
    }
    for (name in listOf(CONFIG, KNOWN_HOSTS))
        if (name !in files) File(dir, name).delete()
    if (items.none { it.type == Backup.SETTING && it.name == TerminalView.PREF_FONT_SIZE })
        getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE).edit().remove(TerminalView.PREF_FONT_SIZE).apply()
    return Pair(hostCount(paths), keys.size)
}

/* Returns the number of hosts and keys added. */
@Throws(IOException::class)
private fun Context.append(paths: Paths, vault: Vault, key: ByteArray?, items: List<Backup.Item>,
    present: List<String>): Pair<Int, Int> {
    val dir = paths.sshDir
    paths.ensureSshDir()
    val keys = items.filter { it.type == Backup.KEY && it.name !in present }
    /* Checked first, not to stop with only part of the file added. */
    if (vault.names().size + keys.count { sealable(items, it) } > Keys.MAX_COUNT)
        throw IOException("at most ${Keys.MAX_COUNT} keys")
    for (item in keys) {
        storeKey(paths, vault, key, items, item)
        for (suffix in Keys.SUFFIXES) {
            items.find { it.type == Backup.FILE && it.name == item.name + suffix }?.let {
                paths.writePrivate(File(dir, it.name), it.data)
            }
        }
    }
    var hosts = 0
    items.find { it.type == Backup.FILE && it.name == CONFIG }?.let {
        val file = File(dir, CONFIG)
        val config = SshConfig.parse(if (file.isFile) file.readText() else "")
        hosts = config.addMissing(SshConfig.parse(String(it.data))).size
        if (hosts > 0) paths.writePrivate(file, config.toString())
    }
    items.find { it.type == Backup.FILE && it.name == KNOWN_HOSTS }?.let {
        val file = File(dir, KNOWN_HOSTS)
        val text = if (file.isFile) file.readText() else ""
        val known = text.lines().toSet()
        val missing = String(it.data).lines().filter { line -> line.isNotBlank() && line !in known }.distinct()
        if (missing.isNotEmpty()) {
            val start = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
            paths.writePrivate(file, start + missing.joinToString("\n", postfix = "\n"))
        }
    }
    val prefs = getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE)
    for (item in items.filter { it.type == Backup.SETTING })
        if (!prefs.contains(item.name)) putSetting(item)
    useKeyNames(paths, keys.filter { sealable(items, it) }.map { it.name })
    return Pair(hosts, keys.size)
}

/*
 * A key goes into the vault with its public key, which ssh needs to use
 * it from the agent, and if it fits the pipe to ssh-add, see Keys.MAX_SIZE.
 * Otherwise it stays in clear, as on the phone it comes from.
 */
private fun sealable(items: List<Backup.Item>, key: Backup.Item): Boolean =
    key.data.size <= Keys.MAX_SIZE && items.any { it.type == Backup.FILE && it.name == "${key.name}.pub" }

/* Into the vault or in clear, see sealable(). */
@Throws(IOException::class)
private fun storeKey(paths: Paths, vault: Vault, key: ByteArray?, items: List<Backup.Item>, item: Backup.Item) {
    dropStrayFiles(paths, items, item.name)
    if (sealable(items, item))
        vault.add(key ?: throw IOException("keys locked"), item.name, item.data)
    else
        paths.writePrivate(File(paths.sshDir, item.name), item.data)
}

/* A key imported: a public key or certificate of its name the file does not bring is of another key. */
private fun dropStrayFiles(paths: Paths, items: List<Backup.Item>, name: String) {
    for (suffix in Keys.SUFFIXES)
        if (items.none { it.type == Backup.FILE && it.name == name + suffix })
            File(paths.sshDir, name + suffix).delete()
}

/* Settings out of range or unknown are skipped. */
private fun Context.putSetting(item: Backup.Item) {
    if (item.name != TerminalView.PREF_FONT_SIZE) return
    val size = String(item.data).toFloatOrNull() ?: return
    if (size < TerminalView.MIN_FONT_SIZE || size > TerminalView.MAX_FONT_SIZE) return
    getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE).edit().putFloat(item.name, size).apply()
}

@Throws(IOException::class)
private fun hostCount(paths: Paths): Int =
    if (paths.config.isFile) SshConfig.parse(paths.config.readText()).hosts.size else 0

/*
 * Ask a passphrase, twice when choosing one, and pass it to use, which
 * zeroes it, or call cancelled. It is copied out of the fields, never
 * into a String.
 */
private fun Activity.askPassphrase(title: Int, choose: Boolean, cancelled: () -> Unit = {},
    use: (CharArray) -> Unit) {
    fun field(hint: Int) = EditText(this).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setHint(hint)
    }
    val first = field(R.string.passphrase)
    val second = field(R.string.passphrase_again)
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(if (choose) dialogLayout(first, second) else dialogLayout(first))
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(R.string.cancel, null)
        .show()
    var used = false
    dialog.setOnDismissListener {
        first.text.clear()
        second.text.clear()
        if (!used) cancelled()
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val text = first.text
        if (choose && text.length < MIN_PASSPHRASE) {
            first.error = getString(R.string.passphrase_short, MIN_PASSPHRASE)
            return@setOnClickListener
        }
        if (choose && !text.contentEquals(second.text)) {
            second.error = getString(R.string.passphrase_mismatch)
            return@setOnClickListener
        }
        if (text.isEmpty()) return@setOnClickListener
        val pass = CharArray(text.length)
        TextUtils.getChars(text, 0, text.length, pass, 0)
        used = true
        dialog.dismiss()
        use(pass)
    }
}
