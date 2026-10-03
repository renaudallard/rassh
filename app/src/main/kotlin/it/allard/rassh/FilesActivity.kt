package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.webkit.MimeTypeMap
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import it.allard.rassh.sftp.SftpAttrs
import it.allard.rassh.sftp.SftpCancelledException
import it.allard.rassh.sftp.SftpClient
import it.allard.rassh.sftp.SftpEntry
import it.allard.rassh.sftp.SftpException
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * A file browser on a server, speaking SFTP to "ssh -s host sftp" through
 * the pipes of its session. ssh's messages and questions show in a small
 * terminal while connecting. Leaving the browser ends the session.
 */
class FilesActivity : Activity(), Session.Listener, SessionService.Listener {
    private val binding = ServiceBinding(this) { attach(it) }
    private lateinit var list: ListView
    private lateinit var pathView: TextView
    private lateinit var status: TextView
    private lateinit var terminal: TerminalView
    private val adapter by lazy { TwoLineAdapter(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var entries: List<SftpEntry> = emptyList()

    /* The directory shown and the one the server started in, absolute. */
    private var cwd: String? = null
    private var home: String? = null

    /* The remote file a download waits for its destination for. */
    private var downloading: String? = null

    /* The directory an upload waits for its files for. */
    private var uploading: String? = null

    @Volatile
    private var cancelled = false

    /* The dialog of the transfer running, closed with the screen. */
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        findViewById<View>(R.id.root).padForInsets()
        setActionBar(findViewById(R.id.toolbar))
        list = findViewById(R.id.files)
        pathView = findViewById(R.id.path)
        status = findViewById(R.id.status)
        terminal = findViewById(R.id.terminal)
        list.adapter = adapter
        list.emptyView = status
        list.setOnItemClickListener { _, _, position, _ -> open(entries[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            actions(entries[position])
            true
        }
        cwd = savedInstanceState?.getString(STATE_PATH)
        home = savedInstanceState?.getString(STATE_HOME)
        downloading = savedInstanceState?.getString(STATE_DOWNLOAD)
        uploading = savedInstanceState?.getString(STATE_UPLOAD)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        binding.bind()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, cwd)
        outState.putString(STATE_HOME, home)
        outState.putString(STATE_DOWNLOAD, downloading)
        outState.putString(STATE_UPLOAD, uploading)
    }

    /* The terminal screen may have taken the session on the way. */
    override fun onResume() {
        super.onResume()
        session?.let {
            it.listener = this
            terminal.invalidate()
        }
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        session?.let {
            if (it.listener === this) it.listener = null
            if (isFinishing) binding.service?.remove(it)
        }
        binding.service?.removeListener(this)
        binding.unbind()
        worker.shutdown()
        super.onDestroy()
    }

    private fun attach(service: SessionService) {
        service.addListener(this)
        val s = service.find(intent.getIntExtra(EXTRA_SESSION, -1))
        if (s == null) {
            finish()
            return
        }
        session = s
        s.listener = this
        terminal.session = s
        actionBar?.title = s.name
        connect(s)
    }

    /* Start SFTP once ssh is through, it may first ask in the terminal. */
    private fun connect(s: Session) {
        val c = s.sftp
        if (c != null) {
            showTerminal(false)
            load(cwd ?: home ?: ".")
            return
        }
        status.setText(R.string.files_connecting)
        showTerminal(true)
        val input = s.dataInput ?: return
        val output = s.dataOutput ?: return
        /* Kept by the session, whatever becomes of this screen. */
        val first = !s.sftpStarted
        s.sftpStarted = true
        run({
            val client = if (first) {
                try {
                    SftpClient(input, output).also { s.sftp = it }
                } finally {
                    s.sftpReady.countDown()
                }
            } else {
                s.sftpReady.await()
                s.sftp ?: throw IOException(getString(R.string.files_not_connected))
            }
            client.realpath(".")
        }) { start ->
            home = start
            showTerminal(false)
            load(cwd ?: start)
        }
    }

    private fun client() = session?.sftp

    /* Hidden, it takes the keyboard it may have opened for a password along. */
    private fun showTerminal(show: Boolean) {
        terminal.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) window.insetsController?.hide(WindowInsets.Type.ime())
    }

    private fun load(path: String) {
        val c = client() ?: return
        run({
            val dir = c.realpath(path)
            Pair(dir, c.list(dir).sortedWith(compareBy({ !it.attrs.isDirectory }, { it.name.lowercase() })))
        }) { (dir, found) ->
            cwd = dir
            entries = found
            pathView.text = dir
            status.setText(R.string.files_empty)
            adapter.items = found.map { Pair(label(it), describe(it.attrs)) }
            list.setSelection(0)
        }
    }

    private fun label(e: SftpEntry): String = when {
        e.attrs.isDirectory -> e.name + "/"
        e.attrs.isLink -> e.name + "@"
        else -> e.name
    }

    private fun describe(a: SftpAttrs): String {
        val parts = mutableListOf<String>()
        if (!a.isDirectory) a.size?.let { parts.add(Formatter.formatShortFileSize(this, it)) }
        a.mtime?.let { parts.add(DATE.format(Date(it * 1000))) }
        a.permissions?.let { parts.add(mode(it)) }
        return parts.joinToString("  ")
    }

    private fun path(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

    /* A directory is entered, a link followed when it leads to one. */
    private fun open(e: SftpEntry) {
        val c = client() ?: return
        val dir = cwd ?: return
        val p = path(dir, e.name)
        when {
            e.attrs.isDirectory -> load(p)
            e.attrs.isLink -> run({ c.stat(p) }) { if (it.isDirectory) load(p) else actions(e, dir) }
            else -> actions(e, dir)
        }
    }

    /*
     * The entry is in dir, the directory listed when it was picked: the
     * listing may change before an action is confirmed.
     */
    private fun actions(e: SftpEntry, dir: String? = cwd) {
        dir ?: return
        val items = mutableListOf<Pair<Int, () -> Unit>>()
        if (!e.attrs.isDirectory) items.add(R.string.download to { download(e, dir) })
        items.add(R.string.rename to { rename(e, dir) })
        items.add(R.string.delete to { delete(e, dir) })
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(items.map { getString(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun back() {
        val dir = cwd
        if (dir == null || dir == home || dir == "/") {
            finish()
            return
        }
        load(dir.substringBeforeLast('/').ifEmpty { "/" })
    }

    private fun download(e: SftpEntry, dir: String) {
        downloading = path(dir, e.name)
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase())
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type ?: "application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, e.name),
            REQUEST_DOWNLOAD)
    }

    private fun upload() {
        uploading = cwd ?: return
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQUEST_UPLOAD)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_DOWNLOAD -> {
                val remote = downloading ?: return
                val uri = data.data ?: return
                downloading = null
                saveTo(remote, uri)
            }
            REQUEST_UPLOAD -> {
                val dir = uploading ?: return
                uploading = null
                val clip = data.clipData
                val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                    else listOfNotNull(data.data)
                if (uris.isNotEmpty()) confirmUpload(uris, dir)
            }
        }
    }

    private fun saveTo(remote: String, uri: Uri) {
        val c = client() ?: return
        val name = remote.substringAfterLast('/')
        transfer(getString(R.string.downloading, name), { progress ->
            try {
                val out = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")
                out.use { c.download(remote, it, progress) }
            } catch (e: IOException) {
                /* Not to leave a partial or empty file behind. */
                try {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (_: Exception) {
                }
                throw e
            }
        }) { toast(getString(R.string.downloaded, name)) }
    }

    /* Names of the device files, made safe to use on the server. */
    private fun remoteName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val safe = name?.replace('/', '_')?.trim()
        return if (safe.isNullOrEmpty() || safe == "." || safe == "..") "upload" else safe
    }

    /* The server says what exists in dir, the listing shown may be another one. */
    private fun confirmUpload(uris: List<Uri>, dir: String) {
        val c = client() ?: return
        val names = uris.map { remoteName(it) }
        run({ names.any { exists(c, path(dir, it)) } }) { clash ->
            if (!clash) {
                uploadAll(uris, names, dir)
                return@run
            }
            AlertDialog.Builder(this)
                .setMessage(R.string.replace_files)
                .setPositiveButton(R.string.replace) { _, _ -> uploadAll(uris, names, dir) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun exists(c: SftpClient, path: String): Boolean =
        try {
            c.lstat(path)
            true
        } catch (e: SftpException) {
            if (e.status != SftpException.NO_SUCH_FILE) throw e
            false
        }

    private fun uploadAll(uris: List<Uri>, names: List<String>, dir: String) {
        val c = client() ?: return
        transfer(getString(R.string.uploading, names.joinToString(", ")), { progress ->
            try {
                for ((uri, name) in uris.zip(names)) {
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
                    input.use { c.upload(it, path(dir, name), progress) }
                }
            } finally {
                /* Some files may be there even when a later one failed. */
                runOnUiThread { if (!isDestroyed) load(dir) }
            }
        }) {}
    }

    private fun rename(e: SftpEntry, dir: String) {
        val c = client() ?: return
        val field = pathField(e.name)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(dialogLayout(field))
            .setPositiveButton(R.string.rename, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val to = field.text.toString().trim()
            if (to.isEmpty() || '/' in to || to == "." || to == "..") {
                field.error = getString(R.string.error_file_name)
                return@setOnClickListener
            }
            dialog.dismiss()
            run({ c.rename(path(dir, e.name), path(dir, to)) }) { load(dir) }
        }
    }

    private fun delete(e: SftpEntry, dir: String) {
        val c = client() ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_file, e.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                val p = path(dir, e.name)
                run({ if (e.attrs.isDirectory) c.rmdir(p) else c.remove(p) }) { load(dir) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        val c = client() ?: return
        val dir = cwd ?: return
        val field = pathField("")
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_folder)
            .setView(dialogLayout(field))
            .setPositiveButton(R.string.create, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = field.text.toString().trim()
            if (name.isEmpty() || '/' in name || name == "." || name == "..") {
                field.error = getString(R.string.error_file_name)
                return@setOnClickListener
            }
            dialog.dismiss()
            run({ c.mkdir(path(dir, name)) }) { load(dir) }
        }
    }

    /*
     * Run task on the worker, then done with its result on the main
     * thread. Errors are shown, the browser stays on what it had.
     */
    private fun <T> run(task: () -> T, done: (T) -> Unit) {
        worker.execute {
            val result = try {
                Result.success(task())
            } catch (e: IOException) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(done) { failed(it) }
            }
        }
    }

    private fun failed(e: Throwable) {
        if (e is SftpCancelledException) return
        if (session?.sftp == null) status.setText(R.string.files_not_connected)
        toast(getString(R.string.files_failed, e.message))
    }

    /* A transfer with a progress dialog that can stop it. */
    private fun transfer(title: String, task: (progress: (Long) -> Boolean) -> Unit, done: () -> Unit) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.isIndeterminate = true
        val count = TextView(this)
        cancelled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogLayout(bar, count))
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        progressDialog = dialog
        var shown = 0L
        run({
            task { bytes ->
                val now = System.nanoTime()
                if (now - shown > PROGRESS_NANOS) {
                    shown = now
                    runOnUiThread { count.text = Formatter.formatShortFileSize(this, bytes) }
                }
                !cancelled
            }
        }) {
            dialog.dismiss()
            done()
        }
        /* An error or a cancel leaves the dialog to close here, if the screen is still there. */
        worker.execute {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (dialog.isShowing) dialog.dismiss()
                if (progressDialog === dialog) progressDialog = null
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_UPLOAD, 0, R.string.upload)
        menu.add(Menu.NONE, MENU_NEW_FOLDER, 1, R.string.new_folder)
        menu.add(Menu.NONE, MENU_REFRESH, 2, R.string.refresh)
        menu.add(Menu.NONE, MENU_TERMINAL, 3, R.string.show_terminal)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_UPLOAD -> if (client() != null) upload()
            MENU_NEW_FOLDER -> newFolder()
            MENU_REFRESH -> cwd?.let { load(it) }
            MENU_TERMINAL -> showTerminal(terminal.visibility != View.VISIBLE)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onUpdate() {
        terminal.invalidate()
    }

    override fun onTitleChanged() {}

    /* ssh gone, the terminal tells why. */
    override fun sessionsChanged() {
        val s = session ?: return
        if (s !in (binding.service?.sessions ?: return)) {
            finish()
            return
        }
        if (!s.isRunning) {
            status.setText(R.string.files_not_connected)
            entries = emptyList()
            adapter.items = emptyList()
            showTerminal(true)
            offerNewHostKey(Paths(this), s)
        }
    }

    companion object {
        private const val STATE_PATH = "path"
        private const val STATE_HOME = "home"
        private const val STATE_DOWNLOAD = "download"
        private const val STATE_UPLOAD = "upload"
        private const val REQUEST_DOWNLOAD = 1
        private const val REQUEST_UPLOAD = 2
        private const val MENU_UPLOAD = 1
        private const val MENU_NEW_FOLDER = 2
        private const val MENU_REFRESH = 3
        private const val MENU_TERMINAL = 4
        private const val PROGRESS_NANOS = 200_000_000L
        private val DATE = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        /* Permissions as ls -l shows them. */
        fun mode(p: Int): String {
            val type = when (p and SftpAttrs.S_IFMT) {
                SftpAttrs.S_IFDIR -> 'd'
                SftpAttrs.S_IFLNK -> 'l'
                else -> '-'
            }
            return type + (0 until 9).map { if (p and (0x100 shr it) != 0) "rwx"[it % 3] else '-' }.joinToString("")
        }
    }
}
