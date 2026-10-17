package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.text.InputType
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import it.allard.rassh.backup.Backup
import it.allard.rassh.config.SshConfig
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.concurrent.thread

/* Moving hosts, keys and settings to another phone, see Backup. */

/* Far above what Keys.MAX_COUNT keys and a config take, the most read of a file of ~/.ssh as well. */
private const val MAX_EXPORT_SIZE = Paths.MAX_FILE
/* The keys of the vault are in the export in clear, the passphrase alone keeps them from a guess. */
private const val MIN_PASSPHRASE = 12
private const val CONFIG = "config"
private const val KNOWN_HOSTS = "known_hosts"

/**
 * Seal the hosts, keys and settings into uri with a passphrase. The file
 * picker created uri or picked a file to replace: when the export does
 * not happen, a new file is deleted, one replaced only once written to.
 */
fun Activity.exportTo(uri: Uri, paths: Paths, vault: Vault) {
    fun discard() {
        if (isEmptyDocument(uri)) deleteDocument(uri)
    }
    /* A vault it cannot read is said before the passphrase is asked. */
    val names = try {
        vault.names()
    } catch (e: IOException) {
        discard()
        toast(getString(R.string.vault_error, e.message))
        return
    }
    /*
     * A key named as 0.1.2 and 0.1.3 allowed, or in the local shell,
     * refused by every import, said before the passphrase and the
     * fingerprint are asked. Left out, it would be lost unnoticed.
     */
    val misnamed = names.sorted().firstOrNull { !Keys.isValidName(it) } ?: Keys.misnamed(paths.sshDir).firstOrNull()
    if (misnamed != null) {
        discard()
        toast(getString(R.string.export_failed, getString(R.string.export_bad_name, misnamed)))
        return
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
                /* Truncated once opened, the file replaced is lost then and goes as well. */
                var opened = false
                val error = try {
                    val sealed = Backup.seal(pass, items)
                    val out = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")
                    opened = true
                    out.use { it.write(sealed) }
                    null
                } catch (e: Exception) {
                    if (!isFileError(e)) throw e
                    if (opened) deleteDocument(uri) else discard()
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
        /* Keys put in the vault meanwhile stop the export, see exportItems(). */
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
    /* Zeroed with the items on a failure, some may not be among them yet. */
    var secrets = emptyList<ByteArray>()
    try {
        for (name in listOf(CONFIG, KNOWN_HOSTS)) {
            val f = File(paths.sshDir, name)
            if (f.isFile) items.add(Backup.Item(Backup.FILE, name, Paths.bytesOf(f)))
        }
        val sealed = vault.names()
        val names = Keys.list(paths.sshDir, vault)
        /* From one reading of the vault. */
        val inVault = names.filter { it in sealed }
        if (inVault.isNotEmpty()) secrets = vault.readAll(key ?: throw IOException("keys locked"), inVault)
        for (name in names) {
            val i = inVault.indexOf(name)
            val secret = if (i >= 0) secrets[i] else Paths.bytesOf(File(paths.sshDir, name), Paths.MAX_FILE)
            items.add(Backup.Item(Backup.KEY, name, secret))
            for (suffix in Keys.suffixes(name, names)) {
                val file = File(paths.sshDir, name + suffix)
                if (file.isFile) items.add(Backup.Item(Backup.FILE, file.name, Paths.bytesOf(file, Paths.MAX_FILE)))
            }
        }
        val prefs = getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(TerminalView.PREF_FONT_SIZE)) {
            val size = prefs.getFloat(TerminalView.PREF_FONT_SIZE, 0f).toString()
            items.add(Backup.Item(Backup.SETTING, TerminalView.PREF_FONT_SIZE, size.toByteArray()))
        }
    } catch (e: GeneralSecurityException) {
        items.forEach { it.data.fill(0) }
        secrets.forEach { it.fill(0) }
        throw IOException(e)
    } catch (e: IOException) {
        items.forEach { it.data.fill(0) }
        secrets.forEach { it.fill(0) }
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
            var merged: Merged? = null
            var here: KnownHere? = null
            var refused: Pair<String, String>? = null
            val error = try {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
                val sealed = input.use { it.readNBytes(MAX_EXPORT_SIZE + 1) }
                if (sealed.size > MAX_EXPORT_SIZE) throw IOException("file too large")
                val opened = checked(Backup.open(sealed, pass))
                try {
                    /* ssh may run Match exec, not on the UI thread. */
                    if (!replace && opened.any { it.type == Backup.FILE && it.name == CONFIG }) {
                        /* Two ssh -G for each host, its names reached and the check: a long config takes a while. */
                        runOnUiThread { toast(getString(R.string.config_checking)) }
                        merged = merge(paths, opened)?.also {
                            refused = configRefusal(paths, it.text, it.names, it.read, it.added)
                        }
                    }
                    /* One ssh-keygen for each name. */
                    if (!replace) here = knownHere(paths, opened)
                } catch (e: IOException) {
                    opened.forEach { it.data.fill(0) }
                    throw e
                }
                items = opened
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
                val refusal = refused
                if (opened == null) {
                    toast(getString(R.string.restore_failed, error))
                } else if (refusal != null) {
                    opened.forEach { it.data.fill(0) }
                    val message = getString(R.string.restore_failed, refusedMessage(refusal))
                    /* A dialog, a toast would cut ssh's lines short. */
                    if (isDestroyed) applicationContext.toast(message)
                    else AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                } else if (isDestroyed) {
                    /* Adding keys needs a fingerprint prompt, which only a live screen shows. */
                    opened.forEach { it.data.fill(0) }
                    applicationContext.toast(getString(R.string.restore_failed, getString(R.string.import_interrupted)))
                } else {
                    store(paths, vault, opened, replace, merged, here, done)
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
    /* Not one the app could not read back, see Paths.bytesOf(). */
    items.find { it.type == Backup.FILE && (it.name == CONFIG || it.name == KNOWN_HOSTS) && it.data.size > Paths.MAX_TEXT }?.let { big ->
        items.forEach { it.data.fill(0) }
        throw IOException("${big.name} is too large")
    }
    return items
}

/* Store the items, which are zeroed afterwards, asking for a fingerprint when keys are to be added. */
private fun Activity.store(paths: Paths, vault: Vault, items: List<Backup.Item>, replace: Boolean, merged: Merged?,
    here: KnownHere?, done: () -> Unit) {
    fun zero() = items.forEach { it.data.fill(0) }
    val present = try {
        Keys.list(paths.sshDir, vault)
    } catch (e: IOException) {
        zero()
        toast(getString(R.string.vault_error, e.message))
        return
    }
    /* Without a vault, as before a fingerprint is enrolled, keys come in clear. */
    val seal = vault.isSetUp
    fun write(key: ByteArray?) {
        try {
            val (hosts, keys) = if (replace) replaceAll(paths, vault, key, items, present, seal)
                else append(paths, vault, key, items, present, seal, merged, here)
            /* Any host may be another server now, as when edited. The provider may be slow. */
            if (replace) thread(name = "drop-resume") { applicationContext.dropResumables() }
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
    if (items.none { it.type == Backup.KEY && (replace || !Keys.isTaken(paths.sshDir, it.name, present)) && sealable(items, it, seal) })
        return write(null)
    /* Android closes the prompt of an app left while the file was read, which must not go unsaid. */
    withVaultKey(vault, getString(R.string.import_reason), { zero() }, {
        zero()
        toast(getString(R.string.restore_failed, getString(R.string.import_dismissed)))
    }) { key -> write(key) }
}

/* Returns the number of hosts and keys there are now. */
@Throws(IOException::class)
private fun Context.replaceAll(paths: Paths, vault: Vault, key: ByteArray?, items: List<Backup.Item>,
    present: List<String>, seal: Boolean): Pair<Int, Int> {
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
    val sealed = keys.filter { sealable(items, it, seal) }.map { it.name }.toSet()
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
    val names = keys.map { it.name }
    for (item in keys) {
        dropStrayFiles(paths, items, item.name, names)
        if (item.name !in sealed) paths.writePrivate(File(dir, item.name), item.data)
    }
    val files = items.filter { it.type == Backup.FILE }.map { it.name }.toSet()
    for (name in present) {
        /* The imported copy replaces the one of the same name. */
        if (name in sealed) {
            File(dir, name).delete()
        } else if (name !in clear) {
            File(dir, name).delete()
            /* Not one the file brings: x-cert.pub of an old key x-cert may be the certificate of x. */
            for (suffix in Keys.suffixes(name, names))
                if (name + suffix !in files) File(dir, name + suffix).delete()
        }
    }
    for (name in listOf(CONFIG, KNOWN_HOSTS))
        if (name !in files) File(dir, name).delete()
    if (items.none { it.type == Backup.SETTING && it.name == TerminalView.PREF_FONT_SIZE })
        getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE).edit().remove(TerminalView.PREF_FONT_SIZE).apply()
    return Pair(hostCount(paths), keys.size)
}

/*
 * The config with the hosts of an export added, the text it was made
 * from, the hosts added and the names ssh must take it for, see
 * checkedNames().
 */
private class Merged(val read: String, val text: String, val added: List<String>, val names: List<String>)

/* The hosts of items added to the config, null when there are none to add. */
@Throws(IOException::class)
private fun merge(paths: Paths, items: List<Backup.Item>): Merged? {
    val theirs = items.find { it.type == Backup.FILE && it.name == CONFIG } ?: return null
    val read = paths.textOf(paths.config)
    val mine = SshConfig.parse(read)
    /* Of patterns too, an export's host may be the jump host of the hosts one matches. */
    val reached = reachedNames(paths, read, checkedNames(mine, mine.patterns))
    val added = mine.addMissing(SshConfig.parse(Paths.utf8(theirs.data, CONFIG)), reached)
    if (added.isEmpty()) return null
    return Merged(read, mine.toString(), added, checkedNames(mine, added))
}

/* Returns the number of hosts and keys added. */
@Throws(IOException::class)
private fun Context.append(paths: Paths, vault: Vault, key: ByteArray?, items: List<Backup.Item>,
    present: List<String>, seal: Boolean, merged: Merged?, here: KnownHere?): Pair<Int, Int> {
    val dir = paths.sshDir
    val configFile = File(dir, CONFIG)
    /* What ssh checked was made from it. */
    if (merged != null && paths.textOf(configFile) != merged.read)
        throw IOException(getString(R.string.import_config_changed))
    paths.ensureSshDir()
    /* As on the key screen, a lone public key or certificate keeps its name. */
    val keys = items.filter { it.type == Backup.KEY && !Keys.isTaken(dir, it.name, present) }
    /* Checked and read first, not to stop with only part of the file added. */
    if (vault.names().size + keys.count { sealable(items, it, seal) } > Keys.MAX_COUNT)
        throw IOException("at most ${Keys.MAX_COUNT} keys")
    val knownFile = File(dir, KNOWN_HOSTS)
    /* What ssh-keygen looked in, as for the config. */
    if (here != null && paths.textOf(knownFile) != here.read)
        throw IOException(getString(R.string.import_config_changed))
    val hosts = merged?.added?.size ?: 0
    val configText = merged?.text
    val knownText = here?.let { known ->
        val text = known.read
        val lines = knownLines(text).toSet()
        /* A pattern is left out, ssh-keygen cannot tell which hosts known here it matches. */
        val missing = knownLines(known.theirs).filter { line ->
            val k = knownNames(line)
            line.isNotBlank() && line !in lines &&
                (k == null || !hasPattern(k.second) && k.second.none { (tag(k.first) to it) in known.found })
        }.distinct()
        val start = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
        if (missing.isEmpty()) null else start + missing.joinToString("\n", postfix = "\n")
    }
    /* Merged and checked before anything is written. */
    for ((name, text) in listOf(CONFIG to configText, KNOWN_HOSTS to knownText))
        if (text != null) Paths.checkText(name, text)
    val names = present + keys.map { it.name }
    /*
     * Into the vault or in clear, see sealable(), the vault in one write
     * once the stray files of their names went: a key must not end up
     * paired with the public key of another.
     */
    for (item in keys) dropStrayFiles(paths, items, item.name, names)
    val sealed = keys.filter { sealable(items, it, seal) }
    if (sealed.isNotEmpty()) vault.addAll(key ?: throw IOException("keys locked"), sealed.map { it.name to it.data })
    for (item in keys) {
        if (item !in sealed) paths.writePrivate(File(dir, item.name), item.data)
        for (suffix in Keys.suffixes(item.name, names)) {
            items.find { it.type == Backup.FILE && it.name == item.name + suffix }?.let {
                paths.writePrivate(File(dir, it.name), it.data)
            }
        }
    }
    configText?.let { paths.writeText(configFile, it) }
    knownText?.let { paths.writeText(knownFile, it) }
    val prefs = getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE)
    for (item in items.filter { it.type == Backup.SETTING })
        if (!prefs.contains(item.name)) putSetting(item)
    useKeyNames(paths, keys.filter { sealable(items, it, seal) }.map { it.name })
    return Pair(hosts, keys.size)
}

/*
 * Split on line feeds only, as ssh does: a carriage return within a
 * line, a comment for one, must not make a new one, which could be an
 * @cert-authority line. Only the one ending a CRLF line goes.
 */
private fun knownLines(text: String) = text.split('\n').map { it.removeSuffix("\r") }

/*
 * The known_hosts of an export, the one here as read and the names of
 * the export's lines ssh-keygen finds a line for here, with what it
 * tells of that line, see foundLines(). A name is known here, its key
 * not added, also through a hashed line or a pattern: ssh takes a key
 * any of a host's lines holds, an old one, removed here once changed,
 * would be trusted again.
 */
private class KnownHere(val read: String, val theirs: String, val found: Set<Pair<String, String>>)

/* Null when items hold no known_hosts. */
@Throws(IOException::class)
private fun knownHere(paths: Paths, items: List<Backup.Item>): KnownHere? {
    val item = items.find { it.type == Backup.FILE && it.name == KNOWN_HOSTS } ?: return null
    val file = File(paths.sshDir, KNOWN_HOSTS)
    val read = paths.textOf(file)
    val theirs = Paths.utf8(item.data, KNOWN_HOSTS)
    val names = knownLines(theirs).mapNotNull { knownNames(it)?.second }.filter { !hasPattern(it) }.flatten().toSet()
    val found = if (read.isEmpty()) emptySet() else names.flatMap { name ->
        foundLines(paths, name, file).map { it.first to name }
    }.toSet()
    return KnownHere(read, theirs, found)
}

/* What ssh-keygen -F tells of a line with marker, null for one it does not. */
private fun tag(marker: String): String? = when (marker) {
    "" -> ""
    "@cert-authority" -> "CA"
    else -> null
}

/* Host names of a known_hosts line with a wildcard, which other hosts may match. */
private fun hasPattern(names: List<String>) = names.any { name -> name.any { it == '*' || it == '?' } }

/*
 * The marker of a known_hosts line, empty for a key, and the host names
 * it is for, in lower case as ssh compares them, those negated left out.
 * Null for a comment, a hashed name, which cannot be compared, and a
 * @revoked line, which only takes trust away. See hostfile.c.
 */
private fun knownNames(line: String): Pair<String, List<String>>? {
    val fields = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
    val marker = fields.firstOrNull()?.takeIf { it.startsWith("@") } ?: ""
    val hosts = fields.getOrNull(if (marker.isEmpty()) 0 else 1) ?: return null
    if (hosts.startsWith("#") || hosts.startsWith("|") || marker == "@revoked") return null
    return marker to hosts.split(',').filter { it.isNotEmpty() && !it.startsWith("!") }.map { it.lowercase() }
}

/*
 * A key goes into the vault, when seal tells there is one, with its public
 * key, which ssh needs to use it from the agent, and if it fits the pipe
 * to ssh-add, see Keys.MAX_SIZE. Its public key may not be larger either,
 * it would be taken as missing, see Keys.publicKey(). Otherwise it stays
 * in clear, as on the phone it comes from or as keys do until a
 * fingerprint is enrolled.
 */
private fun sealable(items: List<Backup.Item>, key: Backup.Item, seal: Boolean): Boolean =
    seal && key.data.size <= Keys.MAX_SIZE &&
        items.any { it.type == Backup.FILE && it.name == "${key.name}.pub" && it.data.size <= Keys.MAX_SIZE }

/*
 * A key imported: a public key or certificate of its name the file does
 * not bring is of another key. names are the keys there will be.
 */
private fun dropStrayFiles(paths: Paths, items: List<Backup.Item>, name: String, names: List<String>) {
    for (suffix in Keys.suffixes(name, names))
        if (items.none { it.type == Backup.FILE && it.name == name + suffix })
            File(paths.sshDir, name + suffix).delete()
}

/* Settings out of range or unknown are skipped. */
private fun Context.putSetting(item: Backup.Item) {
    if (item.name != TerminalView.PREF_FONT_SIZE) return
    val size = String(item.data).toFloatOrNull() ?: return
    /* Written so that NaN, in no range, is refused too. */
    if (!(size >= TerminalView.MIN_FONT_SIZE && size <= TerminalView.MAX_FONT_SIZE)) return
    getSharedPreferences(TerminalView.PREFS, Context.MODE_PRIVATE).edit().putFloat(item.name, size).apply()
}

@Throws(IOException::class)
private fun hostCount(paths: Paths): Int = paths.readConfig().hosts.size

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
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
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
    /* Taken away with the screen, the export would leave its empty file. */
    dismissWithScreen(dialog) {
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
