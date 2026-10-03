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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.io.IOException

/** Private keys in ~/.ssh, generated and converted by ssh-keygen. */
class KeysActivity : Activity() {
    private lateinit var paths: Paths
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

    override fun onDestroy() {
        binding.unbind()
        super.onDestroy()
    }

    private fun load() {
        keys = Keys.list(paths.sshDir)
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
        !Keys.isValidName(name) -> getString(R.string.error_key_name)
        File(paths.sshDir, name).exists() || File(paths.sshDir, "$name.pub").exists() ->
            getString(R.string.error_key_exists)
        else -> null
    }

    private fun generate() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val pad = (resources.displayMetrics.density * 20).toInt()
        layout.setPadding(pad, pad / 2, pad, 0)
        val typeLabel = TextView(this)
        typeLabel.setText(R.string.key_type)
        val type = Spinner(this)
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, KEY_TYPES)
        val nameLabel = TextView(this)
        nameLabel.setText(R.string.key_name)
        val name = EditText(this)
        name.isSingleLine = true
        layout.addView(typeLabel)
        layout.addView(type)
        layout.addView(nameLabel)
        layout.addView(name)
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
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "id_imported"

    private fun importKey(uri: Uri) {
        val name = EditText(this)
        name.isSingleLine = true
        name.setText(displayName(uri))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_key)
            .setMessage(R.string.key_name)
            .setView(name)
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
            try {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readNBytes(MAX_KEY_SIZE + 1)
                    if (bytes.size > MAX_KEY_SIZE) throw IOException("file too large")
                    String(bytes, Charsets.UTF_8)
                } ?: throw IOException("cannot open $uri")
                if (!text.startsWith("-----BEGIN ")) throw IOException("not a private key")
                paths.ensureSshDir()
                paths.writePrivate(File(paths.sshDir, n), text)
            } catch (e: IOException) {
                toast(getString(R.string.import_failed, e.message))
                return@setOnClickListener
            } catch (e: ErrnoException) {
                toast(getString(R.string.import_failed, e.message))
                return@setOnClickListener
            }
            derivePublicKey(n)
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
            actions.add(R.string.derive_public_key to { derivePublicKey(name) })
        } else {
            actions.add(R.string.copy_public_key to {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(name, pub))
                toast(getString(R.string.public_key_copied))
            })
            actions.add(R.string.share_public_key to {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pub)
                startActivity(Intent.createChooser(send, name))
            })
        }
        actions.add(R.string.add_to_agent to {
            run(name, paths.add, listOf("ssh-add", File(paths.sshDir, name).path))
        })
        actions.add(R.string.delete to { deleteKey(name) })
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun deleteKey(name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_key, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                File(paths.sshDir, name).delete()
                File(paths.sshDir, "$name.pub").delete()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_GENERATE, 0, R.string.generate_key)
        menu.add(Menu.NONE, MENU_IMPORT, 1, R.string.import_key)
        menu.add(Menu.NONE, MENU_AGENT_LIST, 2, R.string.agent_keys)
        menu.add(Menu.NONE, MENU_AGENT_CLEAR, 3, R.string.agent_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_GENERATE -> generate()
            MENU_IMPORT -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQUEST_IMPORT)
            MENU_AGENT_LIST -> run(getString(R.string.agent_keys), paths.add, listOf("ssh-add", "-l"))
            MENU_AGENT_CLEAR -> run(getString(R.string.agent_clear), paths.add, listOf("ssh-add", "-D"))
            android.R.id.home -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val MENU_GENERATE = 1
        private const val MENU_IMPORT = 2
        private const val MENU_AGENT_LIST = 3
        private const val MENU_AGENT_CLEAR = 4
        private const val REQUEST_IMPORT = 1
        private const val MAX_KEY_SIZE = 65536
        private const val SHELL = "/system/bin/sh"
        private val KEY_TYPES = listOf("ed25519", "ecdsa", "rsa", "mldsa44-ed25519")
    }
}
