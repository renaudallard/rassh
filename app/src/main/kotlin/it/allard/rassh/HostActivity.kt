package it.allard.rassh

import android.app.Activity
import android.os.Bundle
import android.system.ErrnoException
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import it.allard.rassh.config.Host
import it.allard.rassh.config.SshConfig
import java.io.IOException

/** Edit a Host block of ~/.ssh/config. */
class HostActivity : Activity() {
    private lateinit var paths: Paths
    private var original: String? = null
    private var identities: List<String> = emptyList()

    private lateinit var name: EditText
    private lateinit var hostName: EditText
    private lateinit var user: EditText
    private lateinit var port: EditText
    private lateinit var identity: Spinner
    private lateinit var local: EditText
    private lateinit var remote: EditText
    private lateinit var dynamic: EditText
    private lateinit var other: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)
        findViewById<View>(R.id.root).padForInsets()
        setActionBar(findViewById(R.id.toolbar))
        actionBar?.setDisplayHomeAsUpEnabled(true)

        name = findViewById(R.id.name)
        hostName = findViewById(R.id.hostname)
        user = findViewById(R.id.user)
        port = findViewById(R.id.port)
        identity = findViewById(R.id.identity)
        local = findViewById(R.id.local)
        remote = findViewById(R.id.remote)
        dynamic = findViewById(R.id.dynamic)
        other = findViewById(R.id.other)

        paths = Paths(this)
        val config = try {
            readConfig()
        } catch (e: IOException) {
            toast(e.toString())
            finish()
            return
        }
        original = intent.getStringExtra(EXTRA_HOST)
        val host = original?.let { config.find(it) } ?: Host("")
        actionBar?.setTitle(if (original == null) R.string.new_host else R.string.edit_host)

        /* Keys in the vault are named by their public key, see usePublicKeys(). */
        identities = Keys.plaintext(paths.sshDir).map { "~/.ssh/$it" } + try {
            Vault(paths).names().map { "~/.ssh/$it.pub" }
        } catch (_: IOException) {
            emptyList()
        }
        if (host.identityFile.isNotEmpty() && host.identityFile !in identities)
            identities = identities + host.identityFile
        val labels = listOf(getString(R.string.identity_default)) + identities
        identity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        if (savedInstanceState == null) {
            name.setText(host.name)
            hostName.setText(host.hostName)
            user.setText(host.user)
            port.setText(host.port)
            identity.setSelection(identities.indexOf(host.identityFile) + 1)
            local.setText(host.localForwards.joinToString("\n"))
            remote.setText(host.remoteForwards.joinToString("\n"))
            dynamic.setText(host.dynamicForwards.joinToString("\n"))
            other.setText(host.other.joinToString("\n"))
        }
    }

    private fun readConfig(): SshConfig =
        SshConfig.parse(if (paths.config.isFile) paths.config.readText() else "")

    private fun lines(e: EditText): List<String> =
        e.text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun save() {
        val n = name.text.toString().trim()
        val h = hostName.text.toString().trim()
        val u = user.text.toString().trim()
        val p = port.text.toString().trim()
        val config = try {
            readConfig()
        } catch (e: IOException) {
            toast(getString(R.string.config_failed, e.message))
            return
        }
        var ok = true
        if (!Host.isValidName(n)) {
            name.error = getString(R.string.error_name)
            ok = false
        } else if (n != original && config.find(n) != null) {
            name.error = getString(R.string.error_name_used)
            ok = false
        }
        if (!Host.isValidWord(h)) {
            hostName.error = getString(R.string.error_word)
            ok = false
        }
        if (!Host.isValidWord(u)) {
            user.error = getString(R.string.error_word)
            ok = false
        }
        if (!Host.isValidPort(p)) {
            port.error = getString(R.string.error_port)
            ok = false
        }
        if (!lines(other).all { Host.isValidOption(it) }) {
            other.error = getString(R.string.error_option)
            ok = false
        }
        if (!ok) return

        val selected = identity.selectedItemPosition
        val host = Host(
            name = n,
            hostName = h,
            user = u,
            port = p,
            identityFile = if (selected > 0) identities[selected - 1] else "",
            localForwards = lines(local),
            remoteForwards = lines(remote),
            dynamicForwards = lines(dynamic),
            other = lines(other),
        )
        try {
            config.put(original, host)
            paths.ensureSshDir()
            paths.writePrivate(paths.config, config.toString())
        } catch (e: IOException) {
            toast(getString(R.string.config_failed, e.message))
            return
        } catch (e: ErrnoException) {
            toast(getString(R.string.config_failed, e.message))
            return
        }
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SAVE, 0, R.string.save)
            .setIcon(R.drawable.ic_save)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVE -> save()
            android.R.id.home -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val MENU_SAVE = 1
    }
}
