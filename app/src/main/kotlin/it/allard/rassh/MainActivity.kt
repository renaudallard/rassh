package it.allard.rassh

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.system.ErrnoException
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import it.allard.rassh.config.Args
import it.allard.rassh.config.Host
import it.allard.rassh.config.SshConfig
import java.io.IOException
import java.time.LocalDate
import kotlin.concurrent.thread

class MainActivity : Activity(), SessionService.Listener {
    private lateinit var paths: Paths
    private lateinit var vault: Vault
    private var declined = emptySet<String>()
    private var protecting = false

    /* Each vault question is asked once while the vault is missing, see setUp(). */
    private var askedReset = false
    private var toldNoBiometric = false
    private var askedSetUp = false
    private lateinit var adapter: TwoLineAdapter
    private lateinit var quick: EditText
    private var hosts: List<Host> = emptyList()
    private val binding = ServiceBinding(this) {
        it.addListener(this)
        invalidateOptionsMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForInsets()
        setActionBar(findViewById(R.id.toolbar))
        paths = Paths(this)
        vault = Vault(paths)
        try {
            paths.ensureSshDir()
        } catch (e: ErrnoException) {
            toast(e.toString())
        }
        Keys.removeLeftovers(paths.sshDir)

        quick = findViewById(R.id.quick)
        quick.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                quickConnect()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.connect).setOnClickListener { quickConnect() }

        adapter = TwoLineAdapter(this)
        val list = findViewById<ListView>(R.id.hosts)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, position, _ ->
            val name = hosts[position].name
            launch(Launch(name, paths.ssh, SSH + name, paths.home.path, name))
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            hostMenu(view, hosts[position].name)
            true
        }

        binding.bind()
        requestMissingPermissions()
    }

    /*
     * Hosts on the LAN need ACCESS_LOCAL_NETWORK from Android 17 on,
     * earlier versions grant it with INTERNET.
     */
    private fun requestMissingPermissions() {
        val wanted = mutableListOf(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN)
            wanted.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 0)
    }

    override fun onResume() {
        super.onResume()
        loadHosts()
    }

    /*
     * Not from onResume(): a biometric prompt shown while the activity
     * is still starting is cancelled by the system.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) protect()
    }

    /*
     * Set the vault up when the screen shows, so that a fingerprint
     * enrolled since the app started is taken too. Each question is asked
     * once while the vault is missing. The screen gets the focus back when
     * one is answered, which leads to the next one.
     */
    private fun setUp() {
        val question = when {
            vault.exists -> ::askedReset
            !hasStrongBiometric() -> ::toldNoBiometric
            else -> ::askedSetUp
        }
        if (question.get()) return
        question.set(true)
        setUpVault(vault) { protect() }
    }

    /*
     * Move keys left in clear into the vault, not asking twice for the
     * same ones, and point IdentityFile lines at the vault public keys.
     */
    private fun protect() {
        try {
            usePublicKeys(paths, vault.names())
        } catch (e: IOException) {
            toast(getString(R.string.vault_error, e.message))
        } catch (e: ErrnoException) {
            toast(getString(R.string.vault_error, e.message))
        }
        if (!vault.isSetUp) {
            /* Keys refused by a vault that is gone are offered to the next one. */
            declined = emptySet()
            setUp()
            return
        }
        /* A vault lost later, its key invalidated, gets its questions again. */
        askedReset = false
        toldNoBiometric = false
        askedSetUp = false
        if (protecting) return
        val pending = Keys.plaintext(paths.sshDir).toSet()
        if (pending.isEmpty() || pending == declined) return
        protecting = true
        protectKeys(paths, vault) { moved ->
            protecting = false
            if (!moved) declined = pending
        }
    }

    override fun onDestroy() {
        binding.service?.removeListener(this)
        binding.unbind()
        super.onDestroy()
    }

    override fun sessionsChanged() {
        invalidateOptionsMenu()
    }

    private fun readConfig(): SshConfig =
        SshConfig.parse(if (paths.config.isFile) paths.config.readText() else "")

    private fun loadHosts() {
        hosts = try {
            readConfig().hosts
        } catch (e: IOException) {
            toast(e.toString())
            emptyList()
        }
        adapter.items = hosts.map { it.name to summary(it) }
    }

    private fun summary(h: Host): String = buildString {
        if (h.user.isNotEmpty()) append(h.user).append('@')
        append(h.hostName.ifEmpty { h.name })
        if (h.port.isNotEmpty()) append(':').append(h.port)
    }

    private fun quickConnect() {
        val text = quick.text.toString().trim()
        if (text.isEmpty()) return
        val args = Args.split(text)
        if (args == null) {
            toast(getString(R.string.invalid_arguments))
            return
        }
        launch(Launch(text, paths.ssh, SSH + args, paths.home.path, text))
    }

    /*
     * What to start: title, program, arguments, directory and the server
     * it reaches, null for the file browser, which talks to the program
     * through pipes rather than a terminal.
     */
    private class Launch(
        val title: String,
        val path: String,
        val argv: List<String>,
        val cwd: String,
        val server: String?,
    ) {
        val browse: Boolean
            get() = server == null
    }

    /* Start l, asking first when a session to the same server runs already. */
    private fun launch(l: Launch) {
        val service = binding.service ?: return
        if (l.browse || service.sessions.none { it.isRunning && it.server == l.server }) {
            open(service, l)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.second_session, l.server))
            .setPositiveButton(R.string.open_session) { _, _ -> open(service, l) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /*
     * Start argv[0] on a terminal. With keys in the vault, ask for a
     * fingerprint and give the program an agent of its own holding them,
     * see withAgent(). Without it the program runs anyway, for password
     * logins.
     */
    private fun open(service: SessionService, l: Launch) {
        val names = try {
            vault.names()
        } catch (e: IOException) {
            toast(getString(R.string.vault_error, e.message))
            emptyList()
        }
        if (names.isEmpty())
            start(service, l, l.path, l.argv, emptyList())
        else
            unlockAndStart(service, names, l)
    }

    private fun unlockAndStart(service: SessionService, names: List<String>, l: Launch) {
        withVaultKey(vault, getString(R.string.unlock_reason),
            { start(service, l, l.path, l.argv, emptyList()) }) { key ->
            val pipes = try {
                keyPipes(vault, key, names)
            } catch (e: IOException) {
                /* Connect anyway, like when the fingerprint is refused. */
                toast(getString(R.string.vault_error, e.message))
                start(service, l, l.path, l.argv, emptyList())
                return@withVaultKey
            }
            try {
                start(service, l, SHELL, withAgent(clearAfterLogin(l.argv), names.size), pipes)
            } finally {
                pipes.forEach { it.close() }
            }
        }
    }

    private fun start(
        service: SessionService,
        l: Launch,
        path: String,
        argv: List<String>,
        pipes: List<ParcelFileDescriptor>,
    ): Boolean {
        val session = try {
            /*
             * ssh and sftp take ssh's options and destination, scp takes
             * paths. sftp strips the [ ] remote() may have added, ssh would not.
             */
            val target = when (l.argv[0]) {
                "ssh" -> l.argv.drop(1)
                "sftp" -> l.argv.drop(1).dropLast(1) + l.argv.last().removeSurrounding("[", "]")
                else -> null
            }
            service.start(l.title, path, argv, l.cwd, pipes.map { it.fd }.toIntArray(), l.server, l.browse, target)
        } catch (e: IOException) {
            toast(getString(R.string.start_failed, l.argv[0], e.message))
            return false
        }
        val screen = if (l.browse) FilesActivity::class.java else TerminalActivity::class.java
        startActivity(Intent(this, screen).putExtra(EXTRA_SESSION, session.id))
        return true
    }

    /*
     * scp and sftp work relative to shared storage when the app may use
     * it, otherwise relative to the private home directory.
     */
    private fun withStorage(action: (String) -> Unit) {
        val storage = getSystemService(StorageManager::class.java).primaryStorageVolume.directory
        if (Environment.isExternalStorageManager() && storage != null) {
            action(storage.path)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.storage_rationale)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", packageName, null)))
            }
            .setNeutralButton(R.string.continue_anyway) { _, _ -> action(paths.home.path) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sftp(host: String) {
        withStorage { cwd ->
            launch(Launch("sftp $host", paths.sftp, listOf("sftp") + NO_COMMAND + remote(host), cwd, host))
        }
    }

    /* sftp and scp take what follows a : as a path, unless the host is in [ ]. */
    private fun remote(host: String): String = if (':' in host) "[$host]" else host

    private fun scp(host: String) {
        withStorage { cwd ->
            val hint = TextView(this)
            hint.setText(R.string.scp_hint)
            val fromLabel = TextView(this)
            fromLabel.setText(R.string.scp_from)
            val from = pathField(if (cwd == paths.home.path) "" else "Download/")
            val toLabel = TextView(this)
            toLabel.setText(R.string.scp_to)
            val to = pathField(getString(R.string.scp_remote, remote(host)))
            val recursive = CheckBox(this)
            recursive.setText(R.string.scp_recursive)
            val layout = dialogLayout(hint, fromLabel, from, toLabel, to, recursive)

            AlertDialog.Builder(this)
                .setTitle(R.string.scp)
                .setView(layout)
                .setPositiveButton(R.string.copy_files) { _, _ ->
                    val argv = mutableListOf("scp")
                    if (recursive.isChecked) argv.add("-r")
                    argv.add(from.text.toString().trim())
                    argv.add(to.text.toString().trim())
                    launch(Launch("scp $host", paths.scp, argv, cwd, host))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun hostMenu(anchor: View, name: String) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(Menu.NONE, MENU_FILES, 0, R.string.files)
        menu.menu.add(Menu.NONE, MENU_SFTP, 1, R.string.sftp)
        menu.menu.add(Menu.NONE, MENU_SCP, 2, R.string.scp)
        menu.menu.add(Menu.NONE, MENU_EDIT, 3, R.string.edit)
        menu.menu.add(Menu.NONE, MENU_DELETE, 4, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FILES -> launch(Launch(getString(R.string.files_title, name), paths.ssh,
                    SSH + NO_COMMAND + FILES + listOf("-s", name, "sftp"), paths.home.path, null))
                MENU_SFTP -> sftp(name)
                MENU_SCP -> scp(name)
                MENU_EDIT -> startActivity(Intent(this, HostActivity::class.java).putExtra(EXTRA_HOST, name))
                MENU_DELETE -> deleteHost(name)
            }
            true
        }
        menu.show()
    }

    private fun deleteHost(name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_host, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    val config = readConfig()
                    config.remove(name)
                    paths.writePrivate(paths.config, config.toString())
                } catch (e: IOException) {
                    toast(getString(R.string.config_failed, e.message))
                }
                loadHosts()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun about() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        thread {
            val ssh = try {
                runProgram(paths.ssh, listOf("ssh", "-V"), paths.env, paths.home.path)
            } catch (e: IOException) {
                e.toString()
            }
            val licenses = try {
                assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                    assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
            } catch (e: IOException) {
                e.toString()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(R.string.about)
                    .setMessage(getString(R.string.about_text, version, ssh) + "\n\n" + licenses)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_ADD, 0, R.string.add_host)
            .setIcon(R.drawable.ic_add_host)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        /* In the menu, where it is found once the notification is gone. */
        if (binding.service?.sessions?.isNotEmpty() == true)
            menu.add(Menu.NONE, MENU_SESSIONS, 1, R.string.sessions)
        menu.add(Menu.NONE, MENU_KEYS, 2, R.string.keys)
            .setIcon(R.drawable.ic_key)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_EXPORT, 3, R.string.export)
        menu.add(Menu.NONE, MENU_IMPORT, 4, R.string.import_)
        menu.add(Menu.NONE, MENU_ABOUT, 5, R.string.about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD -> startActivity(Intent(this, HostActivity::class.java))
            MENU_SESSIONS -> startActivity(Intent(this, TerminalActivity::class.java))
            MENU_KEYS -> startActivity(Intent(this, KeysActivity::class.java))
            MENU_EXPORT -> startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, "rassh-${LocalDate.now()}.rassh"),
                REQUEST_EXPORT)
            MENU_IMPORT -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQUEST_IMPORT)
            MENU_ABOUT -> about()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_EXPORT -> exportTo(uri, paths, vault)
            REQUEST_IMPORT -> importFrom(uri, paths, vault) { if (!isDestroyed) loadHosts() }
        }
    }

    companion object {
        /*
         * The agent holds the keys while logging in, a server reached with
         * agent forwarding could use them then. A -o from the command line
         * wins over the config, -A typed in quick connect still wins.
         */
        private val SSH = listOf("ssh", "-o", "ForwardAgent=no")

        /*
         * A RemoteCommand of the host, tmux for instance, makes ssh refuse
         * the sftp subsystem, and a tty would garble it. scp passes these
         * itself, sftp does not.
         */
        private val NO_COMMAND = listOf("-o", "RemoteCommand=none", "-o", "RequestTTY=no")
        /* Without the host's forwards, as sftp does: they would clash with a terminal's or go with the browser. */
        private val FILES = listOf("-o", "ClearAllForwardings=yes")
        private const val MENU_ADD = 1
        private const val MENU_SESSIONS = 2
        private const val MENU_KEYS = 3
        private const val MENU_ABOUT = 4
        private const val MENU_EDIT = 5
        private const val MENU_DELETE = 6
        private const val MENU_SFTP = 7
        private const val MENU_SCP = 8
        private const val MENU_EXPORT = 9
        private const val MENU_IMPORT = 10
        private const val MENU_FILES = 11
        private const val REQUEST_EXPORT = 1
        private const val REQUEST_IMPORT = 2
    }
}
