package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import java.text.DateFormat

class TerminalActivity : Activity(), Session.Listener, SessionService.Listener {
    private lateinit var terminal: TerminalView
    private val binding = ServiceBinding(this) { attach(it) }
    private var session: Session? = null

    /* The session to show, kept when the activity is recreated. */
    private var wanted = -1

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
        wanted = savedInstanceState?.getInt(STATE_SESSION, -1) ?: intent.getIntExtra(EXTRA_SESSION, -1)
        binding.bind()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SESSION, session?.id ?: wanted)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wanted = intent.getIntExtra(EXTRA_SESSION, -1)
        binding.service?.let { attach(it) }
    }

    /* Another terminal screen may have taken the listener of this session. */
    override fun onResume() {
        super.onResume()
        session?.let {
            it.listener = this
            actionBar?.title = label(it)
            terminal.refit()
            terminal.invalidate()
        }
    }

    override fun onDestroy() {
        releaseSession()
        binding.service?.removeListener(this)
        binding.unbind()
        super.onDestroy()
    }

    private fun attach(service: SessionService) {
        service.addListener(this)
        show(service.find(wanted) ?: session?.takeIf { it in service.sessions } ?: service.sessions.lastOrNull())
    }

    private fun show(s: Session?) {
        if (s != null && s === session) return
        releaseSession()
        session = s
        terminal.session = s
        if (s == null) {
            finish()
            return
        }
        s.listener = this
        wanted = s.id
        actionBar?.title = label(s)
        terminal.showKeyboard()
        if (!s.isRunning) offerNewHostKey(Paths(this), s)
    }

    private fun releaseSession() {
        session?.let { if (it.listener === this) it.listener = null }
    }

    override fun onUpdate() {
        terminal.invalidate()
        session?.let { if (!it.isRunning) offerNewHostKey(Paths(this), it) }
    }

    override fun onTitleChanged() {
        actionBar?.title = session?.let { label(it) }
    }

    /* The title, with the opening time when other sessions reach the same server. */
    private fun label(s: Session): String {
        val sessions = binding.service?.sessions ?: return s.title
        if (s.server == null || sessions.count { it.server == s.server } < 2) return s.title
        val opened = DateUtils.formatSameDayTime(s.opened, System.currentTimeMillis(),
            DateFormat.SHORT, DateFormat.MEDIUM)
        return getString(R.string.session_label, s.title, opened)
    }

    override fun sessionsChanged() {
        val service = binding.service ?: return
        /* A session to the same server may have come or gone. */
        session?.let { actionBar?.title = label(it) }
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
        val labels = sessions.map { label(it) }.toMutableList()
        labels.add(getString(R.string.new_session))
        AlertDialog.Builder(this)
            .setTitle(R.string.sessions)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < sessions.size) {
                    show(sessions[which])
                } else {
                    /*
                     * The main screen already open rather than another, without
                     * closing a file browser above it. This screen goes, the
                     * sessions stay in the service, and Back from the main screen
                     * leads to what is left below it.
                     */
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    finish()
                }
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
        private const val STATE_SESSION = "session"
        private const val MENU_PASTE = 1
        private const val MENU_KEYBOARD = 2
        private const val MENU_SESSIONS = 3
        private const val MENU_CLOSE = 4
    }
}
