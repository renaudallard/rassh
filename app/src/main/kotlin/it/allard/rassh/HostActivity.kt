package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.system.ErrnoException
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import it.allard.rassh.config.Host
import it.allard.rassh.config.SshConfig
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** Edit a Host block of ~/.ssh/config. */
class HostActivity : Activity() {
    private lateinit var paths: Paths
    /* A save waiting for ssh to check the file. */
    private var checking = false
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
    private lateinit var tmux: CheckBox

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
        tmux = findViewById(R.id.tmux)

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

        /* A key both in clear and in the vault, from a move cut short, is listed once. */
        identities = (Keys.plaintext(paths.sshDir) + try {
            Vault(paths).names()
        } catch (_: IOException) {
            emptyList()
        }).distinct().map { "~/.ssh/$it" }
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
            /* The tmux lines belong to the checkbox, not to the other options. */
            val attach = host.other.any { isTmuxLine(it) }
            tmux.isChecked = attach
            val rest = if (attach) host.other.filterNot { isTmuxLine(it) || isTtyLine(it) } else host.other
            other.setText(rest.joinToString("\n"))
        }
    }

    private fun readConfig(): SshConfig =
        SshConfig.parse(if (paths.config.isFile) paths.config.readText() else "")

    private fun lines(e: EditText): List<String> =
        e.text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun save() {
        if (checking) return
        val n = name.text.toString().trim()
        val h = hostName.text.toString().trim()
        val u = user.text.toString().trim()
        val p = port.text.toString().trim()
        val config = try {
            SshConfig.parse(paths.textOf(paths.config))
        } catch (e: IOException) {
            toast(getString(R.string.config_failed, e.message))
            return
        }
        var ok = true
        if (!Host.isValidName(n)) {
            name.error = getString(R.string.error_name)
            ok = false
        } else if (!n.equals(original, ignoreCase = true) && config.isUsed(n)) {
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
        } else if (tmux.isChecked && lines(other).any { keyword(it) in TMUX_KEYWORDS }) {
            other.error = getString(R.string.error_tmux)
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
            other = lines(other) + if (tmux.isChecked) TMUX_LINES else emptyList(),
        )
        config.put(original, host)
        val text = config.toString()
        checking = true
        /* ssh may run Match exec, not on the UI thread. */
        thread(name = "check-config") {
            var refused: String? = null
            /* Not ssh's verdict, the check itself could not run. */
            var failed: String? = null
            try {
                refused = refusal(text, n)
            } catch (e: IOException) {
                failed = e.message
            }
            runOnUiThread {
                checking = false
                if (isDestroyed) return@runOnUiThread
                if (failed != null) {
                    toast(getString(R.string.config_check_failed, failed))
                    return@runOnUiThread
                }
                if (refused != null) {
                    /* A dialog, a toast would cut ssh's lines short. */
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.config_refused, refused))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                try {
                    paths.ensureSshDir()
                    paths.writePrivate(paths.config, text)
                } catch (e: IOException) {
                    toast(getString(R.string.config_failed, e.message))
                    return@runOnUiThread
                } catch (e: ErrnoException) {
                    toast(getString(R.string.config_failed, e.message))
                    return@runOnUiThread
                }
                finish()
            }
        }
    }

    /*
     * What ssh says of text as its configuration for name, null when it
     * takes it. One bad line makes ssh refuse the whole file, every host
     * would then fail, and HostName or User expand % tokens.
     */
    @Throws(IOException::class)
    private fun refusal(text: String, name: String): String? {
        /* Its own, a check left running by a recreated screen may still use another. */
        val file = File.createTempFile("config", ".check", paths.tmp)
        try {
            paths.writePrivate(file, text)
            /* Not looking the name up: offline, a canonicalized name would fail the check. */
            val argv = listOf("ssh", "-G", "-F", file.path, "-o", "CanonicalizeHostname=no",
                "-o", "CanonicalizePermittedCNAMEs=none", name)
            val (out, status) = execute(paths.ssh, argv, paths.env, paths.home.path)
            return if (status == 0) null else out.replace(file.path, "config")
        } finally {
            file.delete()
        }
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

        /*
         * Attach to the last tmux session or start one, with plain
         * ssh_config lines, so that any OpenSSH does the same.
         */
        private const val TMUX_COMMAND = "tmux a || tmux"
        private val TMUX_LINES = listOf("RemoteCommand $TMUX_COMMAND", "RequestTTY yes")
        private val TMUX_KEYWORDS = setOf("remotecommand", "requesttty")

        private fun keyword(line: String): String? = SshConfig.keyword(line)?.first?.lowercase()

        private fun isTmuxLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { it.first.equals("RemoteCommand", true) && it.second == TMUX_COMMAND } == true

        private fun isTtyLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { it.first.equals("RequestTTY", true) && it.second.equals("yes", true) } == true
    }
}
