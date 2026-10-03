package it.allard.rassh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.system.ErrnoException
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Owns the sessions and keeps the process in the foreground while any
 * is open. All methods run on the main thread.
 */
class SessionService : Service() {
    inner class LocalBinder : Binder() {
        val service: SessionService
            get() = this@SessionService
    }

    fun interface Listener {
        fun sessionsChanged()
    }

    private val binder = LocalBinder()
    private val listeners = mutableSetOf<Listener>()
    private val _sessions = mutableListOf<Session>()
    private var nextId = 1
    private var foreground = false
    private lateinit var paths: Paths
    private lateinit var agent: Agent

    /* Keys unlocked by fingerprint must not stay usable on a locked phone. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            thread(name = "agent-clear") {
                try {
                    runProgram(SHELL, addCommand(listOf("-D")), paths.env, paths.home.path)
                } catch (_: IOException) {
                }
            }
        }
    }

    val sessions: List<Session>
        get() = _sessions

    override fun onCreate() {
        super.onCreate()
        paths = Paths(this)
        agent = Agent(paths)
        /* Early, so that the agent is ready when the first session starts. */
        try {
            agent.start()
        } catch (_: IOException) {
            /* Tried again by start(). */
        }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_sessions),
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /*
         * A foreground start must be honored even if the sessions are gone.
         * The specialUse type only exists from Android 14 on.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIFICATION, notification())
        foreground = true
        if (_sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        for (s in _sessions) s.close()
        _sessions.clear()
        agent.stop()
        super.onDestroy()
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun find(id: Int): Session? = _sessions.find { it.id == id }

    /** Start argv[0] from path in cwd on a new terminal, fds become 3, 4, ... */
    @Throws(IOException::class)
    fun start(
        name: String,
        path: String,
        argv: List<String>,
        cwd: String = paths.home.path,
        fds: IntArray = IntArray(0),
    ): Session {
        try {
            paths.linkPrograms()
        } catch (e: ErrnoException) {
            e.rethrowAsIOException()
        }
        agent.start()
        val prefs = getSharedPreferences(TerminalView.PREFS, MODE_PRIVATE)
        val session = Session(nextId++, name, path, argv, paths.env, cwd, fds,
            prefs.getInt(TerminalView.PREF_COLUMNS, COLUMNS), prefs.getInt(TerminalView.PREF_ROWS, ROWS),
            { getString(R.string.session_exited, it) }, { changed() })
        _sessions.add(session)
        if (!foreground)
            startForegroundService(Intent(this, SessionService::class.java))
        changed()
        return session
    }

    /** Start the agent if it is not running, before asking it something. */
    @Throws(IOException::class)
    fun startAgent() {
        agent.start()
    }

    /** Forget a session, hanging it up if it still runs. */
    fun remove(session: Session) {
        session.close()
        session.listener = null
        _sessions.remove(session)
        if (_sessions.isEmpty() && foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
        changed()
    }

    private fun changed() {
        if (foreground)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        for (l in listeners.toList()) l.sessionsChanged()
    }

    private fun notification(): Notification {
        val n = _sessions.size
        val intent = PendingIntent.getActivity(this, 0,
            Intent(this, TerminalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(resources.getQuantityString(R.plurals.sessions_open, n, n))
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION = 1
        private const val COLUMNS = 80
        private const val ROWS = 24
    }
}
