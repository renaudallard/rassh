package it.allard.rassh

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.system.ErrnoException
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
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
            connect(name, listOf(name))
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            hostMenu(view, hosts[position].name)
            true
        }

        binding.bind()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
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
        connect(text, args)
    }

    private fun connect(name: String, args: List<String>) {
        val service = binding.service ?: return
        val session = try {
            service.start(name, paths.ssh, listOf("ssh") + args)
        } catch (e: IOException) {
            toast(getString(R.string.start_failed, e.message))
            return
        }
        startActivity(Intent(this, TerminalActivity::class.java).putExtra(EXTRA_SESSION, session.id))
    }

    private fun hostMenu(anchor: View, name: String) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(Menu.NONE, MENU_EDIT, 0, R.string.edit)
        menu.menu.add(Menu.NONE, MENU_DELETE, 1, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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
                runProgram(paths.ssh, listOf("ssh", "-V"), paths.env)
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
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (binding.service?.sessions?.isNotEmpty() == true)
            menu.add(Menu.NONE, MENU_SESSIONS, 1, R.string.sessions)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_KEYS, 2, R.string.keys)
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
    }
}
