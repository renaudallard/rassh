package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View

class TerminalActivity : Activity(), Session.Listener, SessionService.Listener {
    private lateinit var terminal: TerminalView
    private val binding = ServiceBinding(this) { attach(it) }
    private var session: Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        findViewById<View>(R.id.root).padForInsets()
        setActionBar(findViewById(R.id.toolbar))
        terminal = findViewById(R.id.terminal)
        val keys = findViewById<ExtraKeysView>(R.id.keys)
        terminal.extraKeys = keys
        keys.listener = terminal
        terminal.onCloseRequest = { closeSession() }
        binding.bind()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        binding.service?.let { attach(it) }
    }

    override fun onDestroy() {
        session?.listener = null
        binding.service?.removeListener(this)
        binding.unbind()
        super.onDestroy()
    }

    private fun attach(service: SessionService) {
        service.addListener(this)
        val id = intent.getIntExtra(EXTRA_SESSION, -1)
        show(service.find(id) ?: session?.takeIf { it in service.sessions } ?: service.sessions.lastOrNull())
    }

    private fun show(s: Session?) {
        if (s != null && s === session) return
        session?.listener = null
        session = s
        terminal.session = s
        if (s == null) {
            finish()
            return
        }
        s.listener = this
        actionBar?.title = s.title
        terminal.showKeyboard()
    }

    override fun onUpdate() {
        terminal.invalidate()
    }

    override fun onTitleChanged() {
        actionBar?.title = session?.title
    }

    override fun sessionsChanged() {
        val service = binding.service ?: return
        if (session?.let { it in service.sessions } != true)
            show(service.sessions.lastOrNull())
    }

    private fun closeSession() {
        val s = session ?: return
        binding.service?.remove(s)
    }

    private fun chooseSession() {
        val service = binding.service ?: return
        val sessions = service.sessions.toList()
        val labels = sessions.map { it.title }.toMutableList()
        labels.add(getString(R.string.new_session))
        AlertDialog.Builder(this)
            .setTitle(R.string.sessions)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < sessions.size) show(sessions[which])
                else startActivity(Intent(this, MainActivity::class.java))
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_PASTE, 0, R.string.paste)
            .setIcon(R.drawable.ic_paste)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_KEYBOARD, 1, R.string.keyboard)
            .setIcon(R.drawable.ic_keyboard)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_SESSIONS, 2, R.string.sessions)
            .setIcon(R.drawable.ic_sessions)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_CLOSE, 3, R.string.close_session)
            .setIcon(R.drawable.ic_close_session)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_PASTE -> terminal.paste()
            MENU_KEYBOARD -> terminal.toggleKeyboard()
            MENU_SESSIONS -> chooseSession()
            MENU_CLOSE -> closeSession()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val MENU_PASTE = 1
        private const val MENU_KEYBOARD = 2
        private const val MENU_SESSIONS = 3
        private const val MENU_CLOSE = 4
    }
}
