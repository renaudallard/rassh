package it.allard.rassh

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.InputType
import android.system.ErrnoException
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import it.allard.rassh.config.Args
import it.allard.rassh.config.Host
import it.allard.rassh.config.SshConfig
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : Activity(), SessionService.Listener {
    private lateinit var paths: Paths
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
        try {
            paths.ensureSshDir()
        } catch (e: ErrnoException) {
            toast(e.toString())
        }

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
            launch(name, paths.ssh, listOf("ssh", name))
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
        launch(text, paths.ssh, listOf("ssh") + args)
    }

    private fun launch(title: String, path: String, argv: List<String>, cwd: String = paths.home.path) {
        val service = binding.service ?: return
        val session = try {
            service.start(title, path, argv, cwd)
        } catch (e: IOException) {
            toast(getString(R.string.start_failed, argv[0], e.message))
            return
        }
        startActivity(Intent(this, TerminalActivity::class.java).putExtra(EXTRA_SESSION, session.id))
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
        withStorage { cwd -> launch("sftp $host", paths.sftp, listOf("sftp", host), cwd) }
    }

    private fun scp(host: String) {
        withStorage { cwd ->
            val layout = LinearLayout(this)
            layout.orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 20).toInt()
            layout.setPadding(pad, pad / 2, pad, 0)
            val hint = TextView(this)
            hint.setText(R.string.scp_hint)
            val fromLabel = TextView(this)
            fromLabel.setText(R.string.scp_from)
            val from = EditText(this)
            from.isSingleLine = true
            from.inputType = PATH_INPUT
            from.setText(if (cwd == paths.home.path) "" else "Download/")
            val toLabel = TextView(this)
            toLabel.setText(R.string.scp_to)
            val to = EditText(this)
            to.isSingleLine = true
            to.inputType = PATH_INPUT
            to.setText(getString(R.string.scp_remote, host))
            val recursive = CheckBox(this)
            recursive.setText(R.string.scp_recursive)
            for (v in listOf(hint, fromLabel, from, toLabel, to, recursive)) layout.addView(v)

            AlertDialog.Builder(this)
                .setTitle(R.string.scp)
                .setView(layout)
                .setPositiveButton(R.string.copy_files) { _, _ ->
                    val argv = mutableListOf("scp")
                    if (recursive.isChecked) argv.add("-r")
                    argv.add(from.text.toString().trim())
                    argv.add(to.text.toString().trim())
                    launch("scp $host", paths.scp, argv, cwd)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun hostMenu(anchor: View, name: String) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(Menu.NONE, MENU_SFTP, 0, R.string.sftp)
        menu.menu.add(Menu.NONE, MENU_SCP, 1, R.string.scp)
        menu.menu.add(Menu.NONE, MENU_EDIT, 2, R.string.edit)
        menu.menu.add(Menu.NONE, MENU_DELETE, 3, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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
        if (binding.service?.sessions?.isNotEmpty() == true)
            menu.add(Menu.NONE, MENU_SESSIONS, 1, R.string.sessions)
                .setIcon(R.drawable.ic_sessions)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_KEYS, 2, R.string.keys)
            .setIcon(R.drawable.ic_key)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_ABOUT, 3, R.string.about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD -> startActivity(Intent(this, HostActivity::class.java))
            MENU_SESSIONS -> startActivity(Intent(this, TerminalActivity::class.java))
            MENU_KEYS -> startActivity(Intent(this, KeysActivity::class.java))
            MENU_ABOUT -> about()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val MENU_ADD = 1
        private const val MENU_SESSIONS = 2
        private const val MENU_KEYS = 3
        private const val MENU_ABOUT = 4
        private const val MENU_EDIT = 5
        private const val MENU_DELETE = 6
        private const val MENU_SFTP = 7
        private const val MENU_SCP = 8
        private const val PATH_INPUT = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
}
