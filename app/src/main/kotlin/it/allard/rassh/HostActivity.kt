package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.system.ErrnoException
import android.text.InputFilter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
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
import it.allard.rassh.config.equalsAscii
import it.allard.rassh.config.lowerAscii
import java.io.IOException
import kotlin.concurrent.thread

/** Edit a Host block of ~/.ssh/config. */
class HostActivity : Activity() {
    private lateinit var paths: Paths
    /* A save waiting for ssh to check the file. */
    private var checking = false
    private var original: String? = null
    /* The host as the editor read it, to tell a change made elsewhere since, a deletion for one. */
    private var loaded: String? = null
    /* Its comments then, see save(). */
    private var loadedComments: ArrayList<String>? = null
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
    /* The keyboard was up when the screen was recreated, Android 17 no longer shows it again. */
    private var keyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideOverlays()
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
        /* Their text goes into the saved state, which must fit what Android passes between processes. */
        for (field in listOf(name, hostName, user, port, local, remote, dynamic, other))
            field.filters = arrayOf(InputFilter.LengthFilter(MAX_FIELD))
        tmux = findViewById(R.id.tmux)
        choicesToggle = findViewById(R.id.choices_toggle)
        choices = findViewById(R.id.choices)
        addChoices()
        choicesToggle.setOnClickListener { showChoices(choices.visibility != View.VISIBLE) }

        paths = Paths(this)
        val config = try {
            paths.readConfig()
        } catch (e: IOException) {
            toast(getString(R.string.config_read_failed, e.message))
            finish()
            return
        }
        original = intent.getStringExtra(EXTRA_HOST)
        val host = original?.let { config.find(it) } ?: Host("")
        /* Cut to fit the fields, its lines would be lost at the next save: such a host is for the local shell. */
        val fields = listOf(host.name, host.hostName, host.user, host.port) +
            listOf(host.localForwards, host.remoteForwards, host.dynamicForwards, host.other).map { it.joinToString("\n") }
        if (fields.any { it.length > MAX_FIELD }) {
            toast(getString(R.string.host_too_large, host.name))
            finish()
            return
        }
        /* Kept across a recreation, null too: a host gone when the editor opened must stay so. */
        loaded = if (savedInstanceState != null) savedInstanceState.getString(STATE_LOADED)
            else original?.let { signature(config.find(it)) }
        loadedComments = if (savedInstanceState != null) savedInstanceState.getStringArrayList(STATE_COMMENTS)
            else original?.let { config.find(it)?.other?.filter { line -> isComment(line) }?.let { ArrayList(it) } }
        actionBar?.setTitle(if (original == null) R.string.new_host else R.string.edit_host)

        /* A key both in clear and in the vault, from a move cut short, is listed once. */
        identities = (Keys.plaintext(paths.sshDir) + try {
            Vault(paths).names()
        } catch (_: IOException) {
            emptyList()
        }).distinct().map { "~/.ssh/$it" }
        /* Kept by name across a recreation, the keys may have changed since. */
        val chosen = savedInstanceState?.getString(STATE_IDENTITY) ?: host.identityFile
        if (chosen.isNotEmpty() && chosen !in identities)
            identities = identities + chosen
        val labels = listOf(getString(R.string.identity_default)) + identities
        identity.isSaveEnabled = false
        identity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        identity.setSelection(identities.indexOf(chosen) + 1)
        /*
         * Without a key of its own, IdentitiesOnly would leave the host
         * with ssh's default files: nothing is written then, nor shown.
         */
        identity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                only.isEnabled = position > 0
                if (position == 0) only.isChecked = false
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (savedInstanceState == null) {
            name.setText(host.name)
            hostName.setText(host.hostName)
            user.setText(host.user)
            port.setText(host.port)
            /* The IdentitiesOnly line of a host with a key belongs to the checkbox. */
            val restrict = host.identityFile.isNotEmpty() && host.other.any { isOnlyLine(it) }
            only.isChecked = restrict
            local.setText(host.localForwards.joinToString("\n"))
            remote.setText(host.remoteForwards.joinToString("\n"))
            dynamic.setText(host.dynamicForwards.joinToString("\n"))
            /*
             * The tmux lines belong to the checkbox, not to the other
             * options, unless another RemoteCommand or RequestTTY line is
             * there too, which a save with the box ticked would refuse.
             */
            val attach = host.other.any { isTmuxLine(it) } &&
                host.other.all { keyword(it) !in TMUX_KEYWORDS || isTmuxLine(it) || isTtyLine(it) }
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
        keyboard = savedInstanceState?.getBoolean(STATE_KEYBOARD) == true
    }

    /* Once it has the focus, the field that had it is the one the keyboard is for. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !keyboard) return
        keyboard = false
        window.insetsController?.show(WindowInsets.Type.ime())
    }

    /*
     * The host as ssh reads it, compared with what the editor loaded: its
     * options by keyword, in their order for one keyword as ssh takes the
     * first, keywords in any case, without comments. One ending the last
     * block goes to a block added below it once the file is read again,
     * and the editor writes the common options and its checkboxes last,
     * neither of which changes the host.
     */
    private fun isComment(line: String) = line.trimStart().startsWith("#")

    private fun signature(host: Host?): String? = host?.copy(other = host.other.mapNotNull { line ->
        SshConfig.keyword(line)?.let { (key, value) -> lowerAscii(key) + " " + value }
    }.sortedBy { it.substringBefore(' ') })?.toString()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_KEYBOARD, window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
        outState.putIntArray(STATE_CHOICES, spinners.map { it.selectedItemPosition }.toIntArray())
        outState.putBoolean(STATE_SHOWN, choices.visibility == View.VISIBLE)
        outState.putString(STATE_IDENTITY, identities.getOrNull(identity.selectedItemPosition - 1).orEmpty())
        outState.putString(STATE_LOADED, loaded)
        outState.putStringArrayList(STATE_COMMENTS, loadedComments)
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
                val (out, status) = dumpConfig(paths, text, name)
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

    /*
     * Split on line feeds only, as ssh does: a carriage return within a
     * line, which ssh reads as part of a comment for one, must not make a
     * new one. trimOption() takes off the one ending a CRLF line.
     */
    private fun lines(e: EditText): List<String> =
        e.text.split('\n').map { SshConfig.trimOption(it) }.filter { it.isNotEmpty() }

    private fun save() {
        if (checking) return
        val n = name.text.toString().trim()
        val h = hostName.text.toString().trim()
        val u = user.text.toString().trim()
        val p = port.text.toString().trim()
        val read = try {
            paths.textOf(paths.config)
        } catch (e: IOException) {
            toast(getString(R.string.config_failed, e.message))
            return
        }
        val config = SshConfig.parse(read)
        /*
         * Its fields would undo what another screen or the local shell did
         * since: bring back a host deleted, or the old name of a key renamed.
         */
        if (original != null && original?.let { signature(config.find(it)) } != loaded) {
            toast(getString(R.string.host_changed, original))
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
        if (!Host.isValidUser(u)) {
            user.error = getString(R.string.error_user)
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
        val set = lines(other).firstNotNullOfOrNull { line ->
            Choices.of(line)?.takeIf { it in chosen }?.let { line to it }
        }
        if (set != null) {
            /* As the line names it, KeepAlive for TCPKeepAlive for one. */
            val (line, choice) = set
            val key = SshConfig.keyword(line)?.first ?: choice.keyword
            other.error = if (equalsAscii(key, choice.keyword)) getString(R.string.error_choice, key)
                else getString(R.string.error_choice_alias, key, choice.keyword)
            ok = false
        }
        val selected = identity.selectedItemPosition
        val restrict = selected > 0 && only.isChecked
        if (restrict && lines(other).any { keyword(it) == "identitiesonly" }) {
            other.error = getString(R.string.error_only)
            ok = false
        }
        if (!ok) {
            /* A field shows its error only when focused, it may be off the screen too. */
            listOf(name, hostName, user, port, other).firstOrNull { it.error != null }?.requestFocus()
            return
        }

        /*
         * A comment loaded with the host that the file no longer holds there
         * went to a block added below it once it was read again: not written
         * back, it would be there twice.
         */
        val now = original?.let { config.find(it)?.other }.orEmpty()
        val moved = loadedComments.orEmpty().filter { it !in now }
        val host = Host(
            name = n,
            hostName = h,
            user = u,
            port = p,
            identityFile = if (selected > 0) identities[selected - 1] else "",
            localForwards = lines(local),
            remoteForwards = lines(remote),
            dynamicForwards = lines(dynamic),
            other = lines(other).filter { it !in moved } + chosen.map { (c, v) -> Choices.line(c, v) } +
                (if (restrict) listOf(ONLY_LINE) else emptyList()) + if (tmux.isChecked) TMUX_LINES else emptyList(),
        )
        config.put(original, host)
        val text = config.toString()
        /* Before ssh checks it. */
        try {
            Paths.checkText(paths.config.name, text)
        } catch (e: IOException) {
            toast(getString(R.string.config_failed, e.message))
            return
        }
        /* The host saved first, ssh's message is most likely about it. */
        val names = checkedNames(config, listOf(n))
        checking = true
        /* One ssh -G for each host, a long config takes a while. */
        actionBar?.setSubtitle(R.string.config_checking)
        /* ssh may run Match exec, not on the UI thread. */
        thread(name = "check-config") {
            var refused: Pair<String, String>? = null
            /* Not ssh's verdict, the check itself could not run. */
            var failed: String? = null
            try {
                refused = configRefusal(paths, text, names, read, listOf(n))
            } catch (e: IOException) {
                failed = e.message
            }
            runOnUiThread {
                checking = false
                if (isDestroyed) return@runOnUiThread
                actionBar?.subtitle = null
                if (failed != null) {
                    toast(getString(R.string.config_check_failed, failed))
                    return@runOnUiThread
                }
                if (refused != null) {
                    /* A dialog, a toast would cut ssh's lines short. */
                    AlertDialog.Builder(this)
                        .setMessage(refusedMessage(refused))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                try {
                    /* Written meanwhile, by an import finishing for one, it would be lost. */
                    if (paths.textOf(paths.config) != read) {
                        toast(getString(R.string.config_changed))
                        return@runOnUiThread
                    }
                    paths.ensureSshDir()
                    paths.writeText(paths.config, text)
                    /*
                     * Edited, the host may be another server: a transfer cut short
                     * there is not resumed. Saved unchanged, it still is.
                     */
                    val edited = n != original || signature(SshConfig.parse(text).find(n)) != loaded
                    if (edited) original?.let { thread(name = "drop-resume") { applicationContext.dropResumable(it) } }
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
        private const val STATE_IDENTITY = "identity"
        private const val STATE_KEYBOARD = "keyboard"
        private const val STATE_LOADED = "loaded"
        private const val MAX_FIELD = 16384
        private const val STATE_COMMENTS = "loaded_comments"

        /*
         * Attach to the last tmux session or start one, with plain
         * ssh_config lines, so that any OpenSSH does the same.
         */
        private const val TMUX_COMMAND = "tmux a || tmux"
        private val TMUX_LINES = listOf("RemoteCommand $TMUX_COMMAND", "RequestTTY yes")
        private val TMUX_KEYWORDS = setOf("remotecommand", "requesttty")

        private const val ONLY_LINE = "IdentitiesOnly yes"

        private fun keyword(line: String): String? = SshConfig.keyword(line)?.first?.let { lowerAscii(it) }

        private fun isTmuxLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { equalsAscii(it.first, "RemoteCommand") && it.second == TMUX_COMMAND } == true

        private fun isOnlyLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { equalsAscii(it.first, "IdentitiesOnly") && equalsAscii(it.second, "yes") } == true

        private fun isTtyLine(line: String): Boolean =
            SshConfig.keyword(line)?.let { equalsAscii(it.first, "RequestTTY") && equalsAscii(it.second, "yes") } == true
    }
}
