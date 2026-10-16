package it.allard.rassh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
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
import kotlin.math.abs

class MainActivity : Activity(), SessionService.Listener {
    private lateinit var paths: Paths
    private lateinit var vault: Vault
    private var declined = emptySet<String>()
    private var toldConfig = false
    private var toldVault = false
    /* The vault names and config time useKeyNames() last went through. */
    private var keyNamesDone: Pair<List<String>, Long>? = null
    private var protecting = false

    /* Each vault question is asked once while the vault is missing, see setUp(). */
    private var askedReset = false
    private var toldNoBiometric = false
    private var askedSetUp = false
    private lateinit var adapter: TwoLineAdapter
    private lateinit var quick: EditText
    private var hosts: List<Host> = emptyList()
    private lateinit var list: ListView

    /*
     * The host a long press holds: its row, the one it was taken from,
     * its menu, whether it moves, the pointer of the finger, where it
     * went down and where it is.
     */
    private var held = -1
    private var heldFrom = -1
    private var heldMenu: PopupMenu? = null
    private var moving = false
    private var finger = -1
    private var downY = 0f
    private var heldY = 0f
    private val binding = ServiceBinding(this) {
        it.addListener(this)
        invalidateOptionsMenu()
    }

    /* The touch listener of the list only watches, the list still takes clicks. */
    @SuppressLint("ClickableViewAccessibility")
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
            toast(getString(R.string.ssh_dir_failed, e.message))
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
        list = findViewById(R.id.hosts)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, position, _ ->
            val name = hosts[position].name
            launch(Launch(name, paths.ssh, SSH + name, paths.home.path, name))
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            held = position
            heldFrom = position
            moving = false
            heldMenu = hostMenu(view, hosts[position].name)
            true
        }
        list.setOnTouchListener { _, e ->
            drag(e)
            false
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
     * same ones, and name vault keys in IdentityFile lines, see useKeyNames().
     */
    private fun protect() {
        /* A vault it cannot read is said once too, see below. */
        val names = try {
            vault.names().also { toldVault = false }
        } catch (e: IOException) {
            if (!toldVault) toast(getString(R.string.vault_error, e.message))
            toldVault = true
            emptyList()
        }
        /*
         * Only when the vault or the config changed, it reads the whole
         * config. The config it cannot rewrite is said once, not at every
         * focus.
         */
        try {
            if (keyNamesDone != Pair(names, paths.config.lastModified())) {
                useKeyNames(paths, names)
                keyNamesDone = Pair(names, paths.config.lastModified())
            }
        } catch (e: IOException) {
            if (!toldConfig) toast(getString(R.string.config_failed, e.message))
            toldConfig = true
        } catch (e: ErrnoException) {
            if (!toldConfig) toast(getString(R.string.config_failed, e.message))
            toldConfig = true
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
        /* Nothing goes into a vault it cannot read, protectKeys() would say so again. */
        if (protecting || toldVault) return
        val pending = Keys.plaintext(paths.sshDir).toSet()
        if (pending.isEmpty() || pending == declined) return
        protecting = true
        protectKeys(paths, vault) { moved ->
            protecting = false
            /* The keys still in clear, some may have moved. */
            if (!moved) declined = Keys.plaintext(paths.sshDir).toSet()
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
        SshConfig.parse(if (paths.config.isFile) Paths.bytesOf(paths.config).toString(Charsets.UTF_8) else "")

    private fun loadHosts() {
        hosts = try {
            readConfig().hosts
        } catch (e: IOException) {
            toast(getString(R.string.config_read_failed, e.message))
            emptyList()
        }
        held = -1
        adapter.held = -1
        adapter.items = hosts.map { it.name to summary(it) }
    }

    /*
     * Once a long press shows the menu of a host, moving the finger
     * closes it and takes the host along, its place in the config is
     * saved when the finger is lifted. The list scrolls while the finger
     * is near its top or bottom edge. The list long-presses the row of
     * the last finger down, or of the one it keeps when that one is
     * lifted, the drag follows the same finger only.
     */
    private fun drag(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = -1
                finger = e.getPointerId(0)
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (held >= 0) {
                val p = e.findPointerIndex(finger)
                if (p < 0) return
                heldY = e.getY(p)
                if (!moving && abs(heldY - downY) > ViewConfiguration.get(this).scaledTouchSlop) {
                    moving = true
                    heldMenu?.dismiss()
                    heldMenu = null
                    adapter.held = held
                }
                if (moving) {
                    follow()
                    list.removeCallbacks(scroll)
                    list.postOnAnimation(scroll)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (held < 0) {
                finger = e.getPointerId(e.actionIndex)
                downY = e.getY(e.actionIndex)
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == finger) {
                if (held >= 0) {
                    drop()
                } else {
                    val p = if (e.actionIndex == 0) 1 else 0
                    finger = e.getPointerId(p)
                    downY = e.getY(p)
                }
            }
            MotionEvent.ACTION_UP -> if (held >= 0) drop()
            MotionEvent.ACTION_CANCEL -> if (held >= 0) loadHosts()
        }
    }

    private fun follow() {
        val to = list.pointToPosition(list.width / 2, heldY.toInt().coerceIn(0, list.height - 1))
        if (to == AdapterView.INVALID_POSITION || to == held) return
        hosts = hosts.toMutableList().apply { add(to, removeAt(held)) }
        held = to
        adapter.held = to
        adapter.items = hosts.map { it.name to summary(it) }
    }

    private val scroll = object : Runnable {
        override fun run() {
            val edge = list.height / 6
            val dy = when {
                heldY < edge -> -SCROLL_STEP
                heldY > list.height - edge -> SCROLL_STEP
                else -> 0
            } * resources.displayMetrics.density
            if (held < 0 || dy == 0f || !list.canScrollList(dy.toInt())) return
            list.scrollListBy(dy.toInt())
            follow()
            list.postOnAnimation(this)
        }
    }

    private fun drop() {
        val name = hosts[held].name
        val to = held
        held = -1
        adapter.held = -1
        list.removeCallbacks(scroll)
        if (to == heldFrom) return
        try {
            val config = SshConfig.parse(paths.textOf(paths.config))
            if (config.move(name, to)) paths.writePrivate(paths.config, config.toString())
            else toast(getString(R.string.move_refused, name))
        } catch (e: IOException) {
            toast(getString(R.string.config_failed, e.message))
        }
        loadHosts()
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
     * Start argv[0] on a terminal. With keys in the vault, offer to end
     * the strays that could read them, ask for a fingerprint and give the
     * program an agent of its own holding them, see withAgent(). Without
     * it the program runs anyway, for password logins.
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
            withoutStrays { unlockAndStart(service, names, l) }
    }

    private fun unlockAndStart(service: SessionService, names: List<String>, l: Launch) {
        /* Cancel or an error connects without keys, a prompt closed with Back or by leaving the app starts nothing. */
        withVaultKey(vault, getString(R.string.unlock_reason), {
            /* Not once the screen is gone, its dialog dismissed then, see dismissWithScreen(). */
            if (!isDestroyed && !isFinishing) start(service, l, l.path, l.argv, emptyList())
        }, {}) { key ->
            /*
             * The prompt outlives a screen recreated meanwhile, as by a
             * theme change, whose service may have gone with it.
             */
            if (isDestroyed || isFinishing) return@withVaultKey
            val pipes = try {
                keyPipes(vault, key, names)
            } catch (e: IOException) {
                /* Connect anyway, like when the fingerprint is refused. */
                toast(getString(R.string.vault_error, e.message))
                start(service, l, l.path, l.argv, emptyList())
                return@withVaultKey
            }
            try {
                start(service, l, SHELL, withAgent(paths, clearAfterLogin(l.argv), names), pipes)
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
        startActivity(Intent(this, screen).forSession(session))
        return true
    }

    /*
     * Android's sh and toybox as the app user, in its home directory, with
     * ssh and the other programs first in PATH. No vault keys go there,
     * the programs only find keys in clear.
     */
    private fun localShell() {
        val service = binding.service ?: return
        val l = Launch(getString(R.string.local_shell), SHELL, listOf("sh"), paths.home.path, LOCAL)
        start(service, l, l.path, l.argv, emptyList())
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
                /* Android warns that some phones have no such screen. */
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", packageName, null)))
                } catch (_: ActivityNotFoundException) {
                    toast(getString(R.string.no_storage_settings))
                }
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

    private fun hostMenu(anchor: View, name: String): PopupMenu {
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
        return menu
    }

    private fun deleteHost(name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_host, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    val config = SshConfig.parse(paths.textOf(paths.config))
                    config.remove(name)
                    paths.writePrivate(paths.config, config.toString())
                    /* A transfer cut short goes with the host, the provider may be slow. */
                    thread(name = "drop-resume") { applicationContext.dropResumable(name) }
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
                getString(R.string.start_failed, "ssh", e.message)
            }
            val licenses = try {
                assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                    assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
            } catch (e: IOException) {
                e.message.orEmpty()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(R.string.about)
                    .setMessage(getString(R.string.about_text, version, ssh) + "\n\n" + licenses)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.privacy) { _, _ -> openPrivacy() }
                    .show()
            }
        }
    }

    /* Google Play wants the privacy policy reachable from the app. */
    private fun openPrivacy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_browser, PRIVACY_URL))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_ADD, 0, R.string.add_host)
            .setIcon(R.drawable.ic_add_host)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        /* In the menu, where it is found once the notification is gone. */
        if (binding.service?.sessions?.isNotEmpty() == true)
            menu.add(Menu.NONE, MENU_SESSIONS, 1, R.string.sessions)
        menu.add(Menu.NONE, MENU_LOCAL, 1, R.string.local_shell)
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
            MENU_LOCAL -> localShell()
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
         * wins over the config, -A typed in quick connect still wins, but
         * ssh only forwards the agent once logged in, emptied by then.
         */
        private val SSH = listOf("ssh", "-o", "ForwardAgent=no")

        /*
         * A RemoteCommand of the host, tmux for instance, makes ssh refuse
         * the sftp subsystem, and a tty would garble it. scp passes these
         * itself, sftp does not.
         */
        private val NO_COMMAND = listOf("-o", "RemoteCommand=none", "-o", "RequestTTY=no")
        /*
         * Without the host's forwards, as sftp does: they would clash with
         * a terminal's or go with the browser. Nor its LocalCommand, as
         * sftp does too: what it prints would go into the SFTP pipe. The
         * one of clearAfterLogin() comes first and still runs.
         */
        private val FILES = listOf("-o", "ClearAllForwardings=yes", "-o", "PermitLocalCommand=no")
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
        private const val MENU_LOCAL = 12
        /*
         * The server of local shells, for the labels of sessions opened
         * together. Empty, as no destination is: a host named localhost
         * is another server.
         */
        private const val LOCAL = ""
        private const val REQUEST_EXPORT = 1
        private const val REQUEST_IMPORT = 2
        /* How far the list scrolls on each frame while a host is held near its edge, in dp. */
        private const val SCROLL_STEP = 8
        private const val PRIVACY_URL = "https://github.com/renaudallard/rassh/blob/main/PRIVACY.md"
    }
}
