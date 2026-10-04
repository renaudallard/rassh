package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.concurrent.thread

/** Private keys, generated and converted by ssh-keygen, kept in the vault. */
class KeysActivity : Activity() {
    private lateinit var paths: Paths
    private lateinit var vault: Vault
    private var declined = emptySet<String>()
    private var protecting = false
    private lateinit var adapter: TwoLineAdapter
    private var keys: List<String> = emptyList()
    private val binding = ServiceBinding(this) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keys)
        findViewById<View>(R.id.root).padForInsets()
        setActionBar(findViewById(R.id.toolbar))
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setTitle(R.string.keys)
        paths = Paths(this)
        vault = Vault(paths)

        adapter = TwoLineAdapter(this)
        val list = findViewById<ListView>(R.id.keys)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, position, _ -> showKey(keys[position]) }
        binding.bind()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /* Not from onResume(), see MainActivity. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) protect()
    }

    /* Move new keys into the vault, not asking twice for the same ones. */
    private fun protect() {
        if (protecting || !vault.isSetUp) return
        val pending = Keys.plaintext(paths.sshDir).toSet()
        if (pending.isEmpty() || pending == declined) return
        protecting = true
        protectKeys(paths, vault) { moved ->
            protecting = false
            /* The keys still in clear, some may have moved. */
            if (!moved) declined = Keys.plaintext(paths.sshDir).toSet()
            load()
        }
    }

    private fun vaultNames(): List<String> =
        try {
            vault.names()
        } catch (e: IOException) {
            toast(getString(R.string.vault_error, e.message))
            emptyList()
        }

    override fun onDestroy() {
        binding.unbind()
        super.onDestroy()
    }

    private fun load() {
        keys = try {
            Keys.list(paths.sshDir, vault)
        } catch (e: IOException) {
            toast(getString(R.string.vault_error, e.message))
            Keys.plaintext(paths.sshDir)
        }
        adapter.items = keys.map { name ->
            val pub = Keys.publicKey(paths.sshDir, name)
            name to (pub?.split(' ')?.let { (it.getOrNull(0) ?: "") + " " + (it.getOrNull(2) ?: "") }?.trim()
                ?: getString(R.string.public_key_missing))
        }
    }

    /* Run argv in a terminal, for programs that may ask a passphrase. */
    private fun run(name: String, path: String, argv: List<String>) {
        val service = binding.service ?: return
        val session = try {
            paths.ensureSshDir()
            service.start(name, path, argv)
        } catch (e: IOException) {
            toast(getString(R.string.start_failed, argv[0], e.message))
            return
        } catch (e: ErrnoException) {
            toast(getString(R.string.start_failed, argv[0], e.message))
            return
        }
        startActivity(Intent(this, TerminalActivity::class.java).putExtra(EXTRA_SESSION, session.id))
    }

    private fun checkName(name: String): String? = when {
        /* Old keys may have such a name, new ones not: the public key of x-cert is the certificate of x. */
        !Keys.isValidName(name) || name.endsWith("-cert") -> getString(R.string.error_key_name)
        Keys.isTaken(paths.sshDir, name, vaultNames()) -> getString(R.string.error_key_exists)
        else -> null
    }

    /*
     * A new key could not be moved into a full vault and would stay in
     * clear. Keys in clear with their public key are on their way there.
     */
    private fun vaultFull(): Boolean {
        val waiting = Keys.plaintext(paths.sshDir).count { Keys.publicKey(paths.sshDir, it) != null }
        if (vaultNames().size + waiting < Keys.MAX_COUNT) return false
        toast(getString(R.string.vault_full, Keys.MAX_COUNT))
        return true
    }

    private fun generate() {
        val typeLabel = TextView(this)
        typeLabel.setText(R.string.key_type)
        val type = Spinner(this)
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, KEY_TYPES)
        val nameLabel = TextView(this)
        nameLabel.setText(R.string.key_name)
        val name = pathField("")
        val layout = dialogLayout(typeLabel, type, nameLabel, name)
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                name.setText("id_" + KEY_TYPES[position].replace('-', '_'))
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.generate_key)
            .setView(layout)
            .setPositiveButton(R.string.generate, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            val error = checkName(n)
            if (error != null) {
                name.error = error
                return@setOnClickListener
            }
            dialog.dismiss()
            val comment = "rassh@" + Build.MODEL.replace(' ', '_')
            run(n, paths.keygen, listOf("ssh-keygen", "-t", KEY_TYPES[type.selectedItemPosition],
                "-f", File(paths.sshDir, n).path, "-C", comment))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && uri != null)
            importKey(uri)
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { Keys.safeName(it) } else null
        } ?: "id_imported"

    private fun importKey(uri: Uri) {
        val name = pathField(displayName(uri))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_key)
            .setMessage(R.string.key_name)
            .setView(dialogLayout(name))
            .setPositiveButton(R.string.import_, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            val error = checkName(n)
            if (error != null) {
                name.error = error
                return@setOnClickListener
            }
            dialog.dismiss()
            /* In a thread, a provider may have to download the file first. */
            thread(name = "import-key") {
                val error = try {
                    /* Kept in a byte array, zeroed after use, never in a String. */
                    val key = contentResolver.openInputStream(uri)?.use { it.readNBytes(Keys.MAX_SIZE + 1) }
                        ?: throw IOException("cannot open $uri")
                    try {
                        if (key.size > Keys.MAX_SIZE) throw IOException("file too large")
                        if (!key.startsWith(PEM_START)) throw IOException("not a private key")
                        paths.ensureSshDir()
                        paths.writePrivate(File(paths.sshDir, n), key)
                    } finally {
                        key.fill(0)
                    }
                    null
                } catch (e: ErrnoException) {
                    e.message
                } catch (e: Exception) {
                    if (!isFileError(e)) throw e
                    e.message
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (error != null) toast(getString(R.string.import_failed, error)) else derivePublicKey(n)
                }
            }
        }
    }

    /* ssh-keygen -y prints the public key, it may ask the passphrase. */
    private fun derivePublicKey(name: String) {
        val key = File(paths.sshDir, name).path
        val script = "\"\$0\" -y -f \"\$1\" > \"\$1.pub.tmp\" && mv \"\$1.pub.tmp\" \"\$1.pub\"; " +
            "s=\$?; rm -f \"\$1.pub.tmp\"; exit \$s"
        run(name, SHELL, listOf("sh", "-c", script, paths.keygen, key))
    }

    private fun showKey(name: String) {
        val pub = Keys.publicKey(paths.sshDir, name)
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (pub == null) {
            /* ssh-keygen reads the key from its file, a key in the vault has none. */
            if (File(paths.sshDir, name).isFile)
                actions.add(R.string.derive_public_key to { derivePublicKey(name) })
        } else {
            actions.add(R.string.show_public_key to { showPublicKey(name, pub) })
            actions.add(R.string.share_public_key to {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pub)
                startActivity(Intent.createChooser(send, name))
            })
        }
        actions.add(R.string.rename to { renameKey(name) })
        actions.add(R.string.delete to { deleteKey(name) })
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showPublicKey(name: String, pub: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(pub)
            .setPositiveButton(R.string.copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(name, pub))
                toast(getString(R.string.public_key_copied))
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun renameKey(name: String) {
        val field = pathField(name)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_key)
            .setView(dialogLayout(field))
            .setPositiveButton(R.string.rename, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val to = field.text.toString().trim()
            if (to == name) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val error = checkName(to)
            if (error != null) {
                field.error = error
                return@setOnClickListener
            }
            /* Its config lines follow the key, the config must be one the app may rewrite. */
            try {
                paths.textOf(paths.config)
            } catch (e: IOException) {
                toast(getString(R.string.config_failed, e.message))
                return@setOnClickListener
            }
            dialog.dismiss()
            if (name in vaultNames())
                withVaultKey(vault, getString(R.string.rename_reason)) { key -> renameKey(name, to, key) }
            else
                renameKey(name, to, null)
        }
    }

    /*
     * Rename the private key, in the vault when key is given, and the
     * files that go with it, then the config lines naming them.
     */
    private fun renameKey(from: String, to: String, key: ByteArray?) {
        val moved = mutableListOf<Pair<File, File>>()
        fun undo() {
            for ((a, b) in moved) b.renameTo(a)
        }
        val suffixes = Keys.suffixes(from, keys)
        for (suffix in suffixes) {
            val a = File(paths.sshDir, from + suffix)
            val b = File(paths.sshDir, to + suffix)
            if (!a.exists()) continue
            if (!a.renameTo(b)) {
                undo()
                toast(getString(R.string.rename_failed, from))
                return
            }
            moved.add(Pair(a, b))
        }
        /* A key in clear has nothing to do with the vault, nor its error. */
        val error = if (key == null) {
            if (File(paths.sshDir, from).renameTo(File(paths.sshDir, to))) null else getString(R.string.rename_failed, from)
        } else {
            try {
                vault.rename(key, from, to)
                null
            } catch (e: IOException) {
                getString(R.string.vault_error, e.message)
            } catch (e: GeneralSecurityException) {
                getString(R.string.vault_error, e.message)
            }
        }
        if (error != null) {
            undo()
            toast(error)
            load()
            return
        }
        try {
            rewriteIdentities(paths, mapOf(from to to) + suffixes.associate { from + it to to + it })
        } catch (e: IOException) {
            toast(getString(R.string.config_failed, e.message))
        } catch (e: ErrnoException) {
            toast(getString(R.string.config_failed, e.message))
        }
        load()
    }

    private fun deleteKey(name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_key, name))
            .setPositiveButton(R.string.delete) { _, _ -> removeKey(name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeKey(name: String) {
        try {
            vault.remove(name)
        } catch (e: IOException) {
            toast(getString(R.string.vault_error, e.message))
            return
        }
        File(paths.sshDir, name).delete()
        for (suffix in Keys.suffixes(name, keys)) File(paths.sshDir, name + suffix).delete()
        if (!isDestroyed) load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_GENERATE, 0, R.string.generate_key)
        menu.add(Menu.NONE, MENU_IMPORT, 1, R.string.import_key)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_GENERATE -> if (!vaultFull()) generate()
            MENU_IMPORT -> if (!vaultFull()) startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQUEST_IMPORT)
            android.R.id.home -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val MENU_GENERATE = 1
        private const val MENU_IMPORT = 2
        private const val REQUEST_IMPORT = 1
        private val PEM_START = "-----BEGIN ".toByteArray()

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
        private val KEY_TYPES = listOf("ed25519", "ecdsa", "rsa", "mldsa44-ed25519")
    }
}
