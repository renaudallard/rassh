package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.system.ErrnoException
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import it.allard.rassh.config.Choices
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
    private lateinit var only: CheckBox
    private lateinit var local: EditText
    private lateinit var remote: EditText
    private lateinit var dynamic: EditText
    private lateinit var other: EditText
    private lateinit var tmux: CheckBox
    private lateinit var choicesToggle: TextView
    private lateinit var choices: LinearLayout
    /* One per entry of Choices.ALL, "Default" first, then its values. */
    private val spinners = mutableListOf<Spinner>()
    private val items = mutableListOf<MutableList<String>>()

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
        only = findViewById(R.id.only)
        local = findViewById(R.id.local)
        remote = findViewById(R.id.remote)
        dynamic = findViewById(R.id.dynamic)
        other = findViewById(R.id.other)
        tmux = findViewById(R.id.tmux)
        choicesToggle = findViewById(R.id.choices_toggle)
        choices = findViewById(R.id.choices)
        addChoices()
        choicesToggle.setOnClickListener { showChoices(choices.visibility != View.VISIBLE) }

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
        /* Without a key of its own, IdentitiesOnly would leave the host with ssh's default files. */
        identity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                only.isEnabled = position > 0
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (savedInstanceState == null) {
            name.setText(host.name)
            hostName.setText(host.hostName)
            user.setText(host.user)
            port.setText(host.port)
            identity.setSelection(identities.indexOf(host.identityFile) + 1)
            /* The IdentitiesOnly line of a host with a key belongs to the checkbox. */
            val restrict = host.identityFile.isNotEmpty() && host.other.any { isOnlyLine(it) }
            only.isChecked = restrict
            local.setText(host.localForwards.joinToString("\n"))
            remote.setText(host.remoteForwards.joinToString("\n"))
            dynamic.setText(host.dynamicForwards.joinToString("\n"))
            /* The tmux lines belong to the checkbox, not to the other options. */
            val attach = host.other.any { isTmuxLine(it) }
            tmux.isChecked = attach
            val rest = host.other.filterNot {
                attach && (isTmuxLine(it) || isTtyLine(it)) || restrict && isOnlyLine(it)
            }
            val (taken, left) = Choices.take(rest)
            Choices.ALL.forEachIndexed { i, c -> spinners[i].setSelection(c.values.indexOf(taken[c]) + 1) }
            showChoices(taken.isNotEmpty())
            other.setText(left.joinToString("\n"))
        } else {
            /* Built in code, the spinners do not keep their state themselves. */
            savedInstanceState.getIntArray(STATE_CHOICES)?.forEachIndexed { i, p -> spinners.getOrNull(i)?.setSelection(p) }
            showChoices(savedInstanceState.getBoolean(STATE_SHOWN))
        }
        if (original != null && host.name == original) showDefaults(config, host)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_CHOICES, spinners.map { it.selectedItemPosition }.toIntArray())
        outState.putBoolean(STATE_SHOWN, choices.visibility == View.VISIBLE)
    }

    private fun addChoices() {
        for (c in Choices.ALL) {
            val row = layoutInflater.inflate(R.layout.choice, choices, false)
            val label = row.findViewById<TextView>(R.id.label)
            val spinner = row.findViewById<Spinner>(R.id.value)
            val values = (listOf(getString(R.string.choice_default)) + c.values).toMutableList()
            spinner.id = View.generateViewId()
            spinner.isSaveEnabled = false
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            label.text = c.keyword
            label.labelFor = spinner.id
            spinners.add(spinner)
            items.add(values)
            choices.addView(row)
        }
    }

    private fun showChoices(show: Boolean) {
        choices.visibility = if (show) View.VISIBLE else View.GONE
        choicesToggle.setText(if (show) R.string.choices_hide else R.string.choices_show)
    }

    /*
     * Name what each choice left unset gives the host, as ssh reads the
     * saved config without the lines of the choices. ssh may run Match
     * exec, not on the UI thread.
     */
    private fun showDefaults(config: SshConfig, host: Host) {
        val name = host.name
        val taken = Choices.take(host.other).first
        config.put(name, host.copy(other = host.other.filterNot { Choices.of(it) in taken }))
        val text = config.toString()
        thread(name = "show-defaults") {
            val values = try {
                val (out, status) = dump(text, name)
                if (status == 0) Choices.effective(out) else emptyMap()
            } catch (_: IOException) {
                emptyMap()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Choices.ALL.forEachIndexed { i, c ->
                    val value = values[c] ?: return@forEachIndexed
                    items[i][0] = getString(R.string.choice_default_value, value)
                    (spinners[i].adapter as ArrayAdapter<*>).notifyDataSetChanged()
                }
            }
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
        } else if (n != original && config.isUsed(n, original)) {
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
        val chosen = Choices.ALL.withIndex().mapNotNull { (i, c) ->
            spinners[i].selectedItemPosition.takeIf { it > 0 }?.let { c to c.values[it - 1] }
        }.toMap()
        val set = lines(other).firstNotNullOfOrNull { line -> Choices.of(line)?.takeIf { it in chosen } }
        if (set != null) {
            other.error = getString(R.string.error_choice, set.keyword)
            ok = false
        }
        val selected = identity.selectedItemPosition
        val restrict = selected > 0 && only.isChecked
        if (restrict && lines(other).any { keyword(it) == "identitiesonly" }) {
            other.error = getString(R.string.error_only)
            ok = false
        }
        if (!ok) return

        val host = Host(
            name = n,
            hostName = h,
            user = u,
            port = p,
            identityFile = if (selected > 0) identities[selected - 1] else "",
            localForwards = lines(local),
            remoteForwards = lines(remote),
            dynamicForwards = lines(dynamic),
            other = lines(other) + chosen.map { (c, v) -> Choices.line(c, v) } +
                (if (restrict) listOf(ONLY_LINE) else emptyList()) + if (tmux.isChecked) TMUX_LINES else emptyList(),
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
        val (out, status) = dump(text, name)
        return if (status == 0) null else out
    }

    /* The output of ssh -G for name with text as its config, and its exit status. */
    @Throws(IOException::class)
    private fun dump(text: String, name: String): Pair<String, Int> {
        /* Its own, a check left running by a recreated screen may still use another. */
        val file = File.createTempFile("config", ".check", paths.tmp)
        try {
            paths.writePrivate(file, text)
            /* Not looking the name up: offline, a canonicalized name would fail the check. */
            val argv = listOf("ssh", "-G", "-F", file.path, "-o", "CanonicalizeHostname=no",
                "-o", "CanonicalizePermittedCNAMEs=none", name)
            val (out, status) = execute(paths.ssh, argv, paths.env, paths.home.path)
            return Pair(out.replace(file.path, "config"), status)
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
        private const val STATE_CHOICES = "choices"
        private const val STATE_SHOWN = "choices_shown"

        /*
         * Attach to the last tmux session or start one, with plain
         * ssh_config lines, so that any OpenSSH does the same.
         */
        private const val TMUX_COMMAND = "tmux a || tmux"
        private val TMUX_LINES = listOf("RemoteCommand $TMUX_COMMAND", "RequestTTY yes")
        private val TMUX_KEYWORDS = setOf("remotecommand", "requesttty")

        private const val ONLY_LINE = "IdentitiesOnly yes"

        private fun keyword(line: String): String? = SshConfig.keyword(line)?.first?.lowercase()

        private fun isTmuxLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { it.first.equals("RemoteCommand", true) && it.second == TMUX_COMMAND } == true

        private fun isOnlyLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { it.first.equals("IdentitiesOnly", true) && it.second.equals("yes", true) } == true

        private fun isTtyLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { it.first.equals("RequestTTY", true) && it.second.equals("yes", true) } == true
    }
}
