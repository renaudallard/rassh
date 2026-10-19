package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import it.allard.rassh.sftp.shownName
import it.allard.rassh.sftp.SftpException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    /* The screen is going away, read by transfers, see transfer(). */
    @Volatile
    private var leaving = false
    /* The folder listed, and the entries shown of it. */
    private var listing: List<Listed> = emptyList()
    private var entries: List<SftpEntry> = emptyList()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    /* Whether a folder was listed, the status says connecting until then. */
    private var listed = false

    /* The directory shown and the one the server started in, absolute. */
    private var cwd: String? = null
    private var home: String? = null

    /* The remote file a download waits for its destination for. */
    private var downloading: String? = null

    /* The directory an upload waits for its files for. */
    private var uploading: String? = null

    /* A picker result waiting for the connection, see onActivityResult(). */
    private var pendingResult: Pair<Int, Intent>? = null

    /* Whether a transfer cut short was offered, once a screen. */
    private var resumeOffered = false

    /* The dialog of the transfer running, closed with the screen. */
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideOverlays()
        setContentView(R.layout.activity_files)
        findViewById<View>(R.id.root).padForInsets()
        setActionBar(findViewById(R.id.toolbar))
        list = findViewById(R.id.files)
        pathView = findViewById(R.id.path)
        pathView.setOnClickListener { goTo() }
        status = findViewById(R.id.status)
        terminal = findViewById(R.id.terminal)
        /* "press Enter to close" once ssh is gone, as in the terminal screen. */
        terminal.onCloseRequest = { finish() }
        terminal.savesSize = false
        list.adapter = adapter
        list.emptyView = status
        list.setOnItemClickListener { _, _, position, _ -> open(entries[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            open(entries[position], false)
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
            terminal.refit()
            terminal.invalidate()
        }
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        /* Not when the session was closed first, from the terminal screen, which ends this one. */
        if (session?.isClosed != true) leaving = true
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
        val s = service.find(intent.getIntExtra(EXTRA_SESSION, -1), intent.getLongExtra(EXTRA_OPENED, -1))
        /* Only a session made for the browser has its pipes. */
        if (s == null || s.dataInput == null) {
            dropPending()
            finish()
            return
        }
        session = s
        s.listener = this
        terminal.session = s
        actionBar?.title = s.name
        /* ssh may have given up before this screen listened, sessionsChanged() then never came. */
        if (s.isRunning) connect(s) else sessionsChanged()
    }

    /* Start SFTP once ssh is through, it may first ask in the terminal. */
    private fun connect(s: Session) {
        val c = s.sftp
        if (c != null) {
            showTerminal(false)
            load(cwd ?: home ?: ".")
            takePending()
            offerResume()
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
        }, { e ->
            /*
             * Said where it stays, as a toast cuts the advice of a bad packet
             * length short, and so is a server refusing REALPATH, which left
             * the browser connecting with nothing to do.
             */
            failed(e)
            if (e !is SftpCancelledException) status.text = getString(R.string.files_failed, e.message)
        }) { start ->
            home = start
            /* Uploads still go there when it cannot be listed, a drop folder for one. */
            if (cwd == null) {
                cwd = start
                pathView.text = shownName(start)
            }
            showTerminal(false)
            load(cwd ?: start)
            takePending()
            offerResume()
        }
    }

    /* Kept by the session once ssh is gone, its pipes closed. */
    private fun client() = session?.takeIf { it.isRunning }?.sftp

    /* The saved host browsed, given to ssh as "-s host sftp" by MainActivity. */
    private fun host(): String? {
        val target = session?.target ?: return null
        val i = target.lastIndexOf("-s")
        return if (i >= 0) target.getOrNull(i + 1) else null
    }

    /* A transfer to this host cut short with an earlier connection, offered once connected again. */
    private fun offerResume() {
        if (resumeOffered) return
        resumeOffered = true
        val host = host() ?: return
        /* Not while another browser of the host goes on with it. */
        if (isResuming(host)) return
        val r = resumable(host) ?: return
        val message = when (r) {
            is Resumable.Download -> getString(R.string.resume_download, shownName(r.remote.substringAfterLast('/')))
            is Resumable.Upload -> getString(R.string.resume_upload, r.names.drop(r.index).joinToString(", ") { shownName(it) })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.resume_title)
            .setMessage(message)
            .setPositiveButton(R.string.resume) { _, _ ->
                when (r) {
                    is Resumable.Download -> saveTo(r.remote, r.uri, r)
                    is Resumable.Upload -> resumeUpload(r)
                }
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                /* The part downloaded goes, the provider may be slow. What an upload left on the server stays. */
                worker.execute { dropResumable(host, r) }
            }
            .setNeutralButton(R.string.later, null)
            .show()
    }

    private fun takePending() {
        val (code, data) = pendingResult ?: return
        pendingResult = null
        handleResult(code, data)
    }

    /* Not connected, or ssh gone: the empty file the picker made for a download goes, as when it fails. */
    private fun dropPending() {
        val (code, data) = pendingResult ?: return
        pendingResult = null
        val uri = data.data ?: return
        /* On the worker, as the provider may be slow, which shutdown() lets finish. */
        if (code == REQUEST_DOWNLOAD) worker.execute { if (isEmptyDocument(uri)) deleteDocument(uri) }
    }

    /* Hidden, it takes the keyboard it may have opened for a password along. */
    private fun showTerminal(show: Boolean) {
        terminal.visibility = if (show) View.VISIBLE else View.GONE
        if (show) terminal.refit()
        if (!show) window.insetsController?.hide(WindowInsets.Type.ime())
    }

    /* An entry listed, with the lines of its row. */
    private class Listed(val entry: SftpEntry, val label: String, val about: String)

    /* up for the parent folder, entered even when it cannot be listed, Back must not get stuck below it. */
    private fun load(path: String, up: Boolean = false) {
        val c = client() ?: return
        val hidden = prefs.getBoolean(PREF_HIDDEN, false)
        /* The rows are made on the worker too, a large folder would hold the screen. */
        run({
            val dir = c.realpath(path)
            /* Made for each listing, a format keeps the time zone it was made in. */
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val found = c.list(dir).map { Listed(it, label(it), describe(it.attrs, date)) }
            Triple(dir, found, arrange(found, hidden))
        }, { e ->
            failed(e)
            /* The folder shows empty with the reason, the status would otherwise stay on connecting. */
            if (client() != null && (!listed || up)) {
                cwd = path
                listing = emptyList()
                entries = emptyList()
                pathView.text = shownName(path)
                status.text = getString(R.string.files_failed, e.message)
                adapter.items = emptyList()
            }
        }) { (dir, found, shown) ->
            listed = true
            cwd = dir
            listing = found
            pathView.text = shownName(dir)
            display(shown)
        }
    }

    /* The entries to show, hidden ones left out unless asked for, as sftp's ls does, folders first. */
    private fun arrange(found: List<Listed>, hidden: Boolean): List<Listed> =
        (if (hidden) found else found.filter { !it.entry.name.startsWith(".") })
            .sortedWith(compareBy({ !it.entry.attrs.isDirectory }, { it.entry.name.lowercase() }))

    /* Arranged again on the worker, the listing may be large. */
    private fun rearrange() {
        val found = listing
        val hidden = prefs.getBoolean(PREF_HIDDEN, false)
        run({ arrange(found, hidden) }) { if (listing === found) display(it) }
    }

    private fun display(shown: List<Listed>) {
        entries = shown.map { it.entry }
        status.setText(if (shown.isEmpty() && listing.isNotEmpty()) R.string.files_only_hidden else R.string.files_empty)
        adapter.items = shown.map { Pair(it.label, it.about) }
        list.setSelection(0)
    }

    private fun label(e: SftpEntry): String = when {
        e.attrs.isDirectory -> shownName(e.name) + "/"
        e.attrs.isLink -> shownName(e.name) + "@"
        else -> shownName(e.name)
    }

    private fun describe(a: SftpAttrs, date: DateFormat): String {
        val parts = mutableListOf<String>()
        if (!a.isDirectory) a.size?.let { parts.add(Formatter.formatShortFileSize(this, it)) }
        a.mtime?.let { parts.add(date.format(Date(it * 1000))) }
        a.permissions?.let { parts.add(mode(it)) }
        return parts.joinToString("  ")
    }

    private fun path(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

    /*
     * A directory is entered, a link followed when it leads to one, with
     * enter, else the actions are offered. A link is looked up first, its
     * target may be a file to download.
     */
    private fun open(e: SftpEntry, enter: Boolean = true) {
        val c = client() ?: return
        val dir = cwd ?: return
        val p = path(dir, e.name)
        when {
            enter && e.attrs.isDirectory -> load(p)
            /* A dangling link still gets its actions, but downloading, on a long press. */
            e.attrs.isLink -> run({ c.stat(p) }, { if (enter) failed(it) else actions(e, dir, false) }) {
                if (enter && it.isDirectory) load(p) else actions(e, dir, it.isFile)
            }
            else -> actions(e, dir)
        }
    }

    /*
     * The entry is in dir, the directory listed when it was picked: the
     * listing may change before an action is confirmed.
     */
    private fun actions(e: SftpEntry, dir: String? = cwd, file: Boolean = e.attrs.isFile) {
        dir ?: return
        val items = mutableListOf<Pair<Int, () -> Unit>>()
        if (file) items.add(R.string.download to { download(e, dir) })
        items.add(R.string.rename to { rename(e, dir) })
        items.add(R.string.delete to { delete(e, dir) })
        AlertDialog.Builder(this)
            .setTitle(shownName(e.name))
            .setItems(items.map { getString(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun back() {
        val dir = cwd
        if (dir == null || dir == home || dir == "/" || session?.isRunning != true) {
            finish()
            return
        }
        load(dir.substringBeforeLast('/').ifEmpty { "/" }, true)
    }

    private fun download(e: SftpEntry, dir: String) {
        client() ?: return
        downloading = path(dir, e.name)
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase())
        pick(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type ?: "application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, shownName(e.name)),
            REQUEST_DOWNLOAD)
    }

    private fun upload() {
        uploading = cwd ?: return
        pick(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQUEST_UPLOAD)
    }

    /*
     * A recreated browser gets the result of the picker before it is
     * connected again, it waits for SFTP then.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (client() != null) {
            handleResult(requestCode, data)
        } else {
            pendingResult = Pair(requestCode, data)
            if (session?.isRunning == false) dropPending()
        }
    }

    private fun handleResult(requestCode: Int, data: Intent) {
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

    /* Download remote into uri, or go on with resumed, a download cut short there. */
    private fun saveTo(remote: String, uri: Uri, resumed: Resumable.Download? = null) {
        val c = client() ?: return
        val host = host()
        if (!startResuming(host, resumed)) return
        val name = shownName(remote.substringAfterLast('/'))
        /* Kept past this screen, it may be resumed after connecting again. */
        val kept = host != null && keepAccess(listOf(uri), true)
        transfer(getString(R.string.downloading, name), { progress, stopped, sized ->
            /* Truncated only once the remote file opened, and only known so once that open returned. */
            var opened = false
            var started: Resumable.Download? = null
            var append: Pair<OutputStream, Long>? = null
            try {
                /* Changed on the phone since, it may be another file now: left alone, as deletePartial() does. */
                if (resumed != null && resumed.length >= 0 && wholeFile(uri) != Pair(resumed.length, resumed.modified))
                    throw IOException(getString(R.string.resume_changed))
                val attrs = c.stat(remote)
                sized(attrs.size ?: -1)
                started = Resumable.Download(remote, uri, attrs.size ?: -1, attrs.mtime)
                /* Changed on the server since, it is another file: downloaded again. */
                if (resumed != null && attrs.size == resumed.size && attrs.mtime == resumed.mtime)
                    append = openAppend(resumed)
                c.download(remote, {
                    /* Only once it writes, a download failing before leaves the file and its record as they were. */
                    forgetDownloadsInto(uri, resumed)
                    (append?.first ?: contentResolver.openOutputStream(uri, "wt") ?: throw IOException(getString(R.string.error_cannot_open)))
                        .also { opened = true }
                }, append?.second ?: 0, progress)
                /* Forgotten while still claimed: a host edited meanwhile would drop the record and the file. */
                ended(host, resumed)
            } catch (e: Exception) {
                if (!isFileError(e)) throw e
                if (!opened) append?.first?.close()
                val cut = host != null && kept && c.isBroken && !stopped()
                if (cut && opened) {
                    /* Cut with the connection, what came is kept to go on from. */
                    keepResumable(host, withFile(started ?: throw e))
                } else if (!cut || resumed == null) {
                    /* Not to leave a partial or empty file behind. Cut again before going on, it stays as it was. */
                    if (opened || resumed == null && isEmptyDocument(uri)) deleteDocument(uri)
                    else if (resumed != null) deletePartial(resumed)
                    ended(host, resumed)
                } else if (isOrphaned(host, resumed)) {
                    /* Unless another browser of the host kept its own in its place meanwhile. */
                    deletePartial(resumed)
                }
                throw e
            } finally {
                /* Let go once forgotten or kept for later, see doneWith(). */
                if (kept) doneWith(listOf(uri), true)
                if (host != null && resumed != null) unclaimResumable(host)
            }
        }) { toast(getString(R.string.downloaded, name)) }
    }

    /*
     * d with the size and time of its file on the phone, to tell when it
     * is resumed whether another app changed the file since.
     */
    private fun withFile(d: Resumable.Download): Resumable.Download {
        val file = wholeFile(d.uri)
        return Resumable.Download(d.remote, d.uri, d.size, d.mtime, file?.first ?: -1, file?.second ?: 0)
    }

    /*
     * The file on the phone d was cut into opened to append to, with its
     * size, when it is a whole regular file of at most the size of the
     * remote one, left as it was when cut. What a pipe holds, or a
     * provider that cannot append would write to, is not known: null
     * then, the download starts again.
     */
    private fun openAppend(d: Resumable.Download): Pair<OutputStream, Long>? {
        if (d.length < 1 || d.length > d.size) return null
        val afd = try {
            contentResolver.openAssetFileDescriptor(d.uri, "wa") ?: return null
        } catch (e: Exception) {
            if (!isFileError(e)) throw e
            return null
        }
        /* At its end, a provider may not open it to append as asked, writing from its start. */
        val end = try {
            if (wholeFile(afd) == Pair(d.length, d.modified)) Os.lseek(afd.fileDescriptor, 0, OsConstants.SEEK_END) else -1L
        } catch (_: ErrnoException) {
            -1L
        }
        if (end != d.length) {
            afd.close()
            return null
        }
        return Pair(afd.createOutputStream(), d.length)
    }

    /*
     * Whether resumed, if any, may go on: once, as two browsers of the host
     * may have offered it, and while kept, as another may have dropped it
     * since, a download into its file for one.
     */
    private fun startResuming(host: String?, resumed: Resumable?): Boolean {
        if (host == null || resumed == null) return true
        if (claimResumable(host, resumed)) return true
        toast(getString(if (isResuming(host)) R.string.resume_elsewhere else R.string.resume_gone))
        return false
    }

    /*
     * Whether the file at uri starts with the bytes r, an upload cut
     * short, had read of it: they are hashed again, the file is read up
     * to there twice. A file changed but for its size and time is not
     * taken for the start of what the server has. progress shows the
     * bytes hashed and may stop it, as during a transfer.
     */
    private fun samePrefix(uri: Uri, r: Resumable.Upload, progress: (Long) -> Boolean): Boolean {
        val input = Hashing(contentResolver.openInputStream(uri) ?: return false)
        input.use {
            val buf = ByteArray(BUFFER_SIZE)
            while (it.count < r.sent) {
                if (it.read(buf, 0, minOf(buf.size.toLong(), r.sent - it.count).toInt()) < 0) return false
                if (!progress(it.count)) throw SftpCancelledException()
            }
        }
        return input.hex() == r.sha256
    }

    /* input, with the SHA-256 and the count of the bytes read from it, the start of it as nothing skips. */
    private class Hashing(input: InputStream) : FilterInputStream(input) {
        private val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
            private set

        override fun read(): Int {
            val c = super.read()
            if (c >= 0) {
                digest.update(c.toByte())
                count++
            }
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                digest.update(b, off, n)
                count += n
            }
            return n
        }

        fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }

    /* A transfer done, stopped, or failed for good: the one it resumed is forgotten. */
    private fun ended(host: String?, resumed: Resumable?) {
        if (host != null && resumed != null) forgetResumable(host, resumed)
    }

    /* Names of the device files, made safe to use on the server. */
    private fun remoteName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        /*
         * Controls as well, a NUL for one, on which sftp-server ends the
         * connection, and what breaks or reorders a line, the names are shown
         * one per line before the upload: one could hide or disguise another.
         */
        val safe = name?.map { if (it == '/' || it.isISOControl() || it in UNSHOWN) '_' else it }
            ?.joinToString("")?.trim()
        /* Over 255 bytes no file system takes it, and a long one makes a request sftp-server refuses. */
        return if (safe.isNullOrEmpty() || safe == "." || safe == ".." || safe.toByteArray().size > 255) "upload" else safe
    }

    /*
     * The names are shown before anything is sent, those that exist on
     * the server too: a provider may give another name than the one the
     * picker showed, that of a file a shell reads at login for one. The
     * server says what exists in dir, the listing shown may be another one.
     */
    private fun confirmUpload(uris: List<Uri>, dir: String) {
        val c = client() ?: return
        /* The names come from the providers, which may be slow, on the worker too. */
        run({
            val names = uris.map { remoteName(it) }
            /* They would replace each other without a word. */
            if (names.toSet().size != names.size) null else Pair(names, names.filter { exists(c, path(dir, it)) })
        }) { found ->
            val (names, existing) = found ?: return@run toast(getString(R.string.upload_same_name))
            val shown = { list: List<String> -> list.joinToString("\n") { shownName(it) } }
            AlertDialog.Builder(this)
                .setTitle(R.string.upload)
                .setMessage(getString(R.string.upload_names, shownName(dir), shown(names)) +
                    if (existing.isEmpty()) "" else "\n\n" + getString(R.string.replace_named, shown(existing)))
                .setPositiveButton(if (existing.isEmpty()) R.string.upload else R.string.replace) { _, _ ->
                    uploadAll(uris, names, dir)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /*
     * The file cut short may be another one now, which the upload would
     * start over, and the files after it may have come to exist since:
     * asked about as for a new upload.
     */
    private fun resumeUpload(r: Resumable.Upload) {
        val c = client() ?: return
        run({
            val cut = r.names.getOrNull(r.index)?.let { path(r.dir, it) }
            /* Without the length, cut before the first write or not from a regular file, only what was sent. */
            val known = if (r.length < 0) r.sent else minOf(r.length, r.sent)
            val other = cut != null && exists(c, cut) && sizeOf(c, cut)?.let { SftpClient.mayHold(it, known) } != true
            other || r.names.drop(r.index + 1).any { exists(c, path(r.dir, it)) }
        }) { clash ->
            replacing(clash) { uploadAll(r.uris, r.names, r.dir, r) }
        }
    }

    /* Call go, once the user agreed to replace the files when some exist. */
    private fun replacing(clash: Boolean, go: () -> Unit) {
        if (!clash) return go()
        AlertDialog.Builder(this)
            .setMessage(R.string.replace_files)
            .setPositiveButton(R.string.replace) { _, _ -> go() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* The size of the file path leads to, null when there is none or the server tells none. */
    private fun sizeOf(c: SftpClient, path: String): Long? =
        try {
            c.stat(path).size
        } catch (e: SftpException) {
            if (e.status != SftpException.NO_SUCH_FILE) throw e
            null
        }

    private fun exists(c: SftpClient, path: String): Boolean =
        try {
            c.lstat(path)
            true
        } catch (e: SftpException) {
            if (e.status != SftpException.NO_SUCH_FILE) throw e
            false
        }

    /* Upload uris as names into dir, or go on with resumed, an upload cut short there. */
    private fun uploadAll(uris: List<Uri>, names: List<String>, dir: String, resumed: Resumable.Upload? = null) {
        val c = client() ?: return
        val host = host()
        if (!startResuming(host, resumed)) return
        val from = resumed?.index ?: 0
        /* Kept past this screen, it may be resumed after connecting again. */
        val kept = host != null && keepAccess(uris, false)
        transfer(getString(R.string.uploading, names.drop(from).joinToString(", ")), { progress, stopped, sized ->
            var index = from
            /* The file at index once the server opened it, to go on with if it is the same then. */
            var length = -1L
            var modified = 0L
            var hashing: Hashing? = null
            /* Whether the file at index is the one resumed, checked. */
            var verified = false
            /* Where it started, -1 before the upload chose, the server file untouched then. */
            var start = -1L
            try {
                while (index < uris.size) {
                    val path = path(dir, names[index])
                    length = -1
                    verified = false
                    start = -1
                    val afd = contentResolver.openAssetFileDescriptor(uris[index], "r")
                        ?: throw IOException(getString(R.string.error_cannot_open))
                    /* The stream closes afd, whatever fails. */
                    Hashing(afd.createInputStream()).also { hashing = it }.use { input ->
                        val file = wholeFile(afd)
                        sized(file?.first ?: -1)
                        /* The same file as when cut short, its size and time first, then what it starts with. */
                        val same = resumed != null && index == resumed.index && file != null &&
                            file.first == resumed.length && file.second == resumed.modified &&
                            samePrefix(uris[index], resumed, progress)
                        verified = same
                        /* Called first before the server opens the file, then after each write. */
                        var calls = 0
                        val counted = { bytes: Long ->
                            if (++calls == 1) start = bytes
                            if (calls == 2 && file != null) {
                                length = file.first
                                modified = file.second
                            }
                            progress(bytes)
                        }
                        if (resumed != null && file != null && same)
                            c.resumeUpload(input, file.first, resumed.sent, path, counted)
                        else
                            c.upload(input, path, counted)
                    }
                    index++
                }
                /* Forgotten while still claimed, see saveTo(). */
                ended(host, resumed)
            } catch (e: Exception) {
                if (!isFileError(e)) throw e
                if (host != null && kept && c.isBroken && !stopped()) {
                    /*
                     * Cut again before the server file was touched, or
                     * before reading past what was checked, as it starts
                     * below what the server has: the record it resumed
                     * still stands, the server holds no more. Not once
                     * started over from 0, the server then holds what was
                     * sent since.
                     */
                    val h = hashing
                    if (resumed != null && index == resumed.index &&
                        (start == -1L || verified && start != 0L && h != null && h.count < resumed.sent))
                        keepResumable(host, resumed)
                    else
                        keepResumable(host, Resumable.Upload(dir, uris, names, index, length, modified,
                            h?.count ?: 0, h?.hex() ?: ""))
                } else
                    ended(host, resumed)
                throw e
            } finally {
                if (kept) doneWith(uris, false)
                if (host != null && resumed != null) unclaimResumable(host)
                /* Some files may be there even when a later one failed. */
                runOnUiThread { if (!isDestroyed) load(dir) }
            }
        }) {}
    }

    /* Dialogs take the client once confirmed, ssh may have exited meanwhile. */
    private fun rename(e: SftpEntry, dir: String) {
        client() ?: return
        val shown = shownName(e.name)
        val field = pathField(shown)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(dialogLayout(field))
            .setPositiveButton(R.string.rename, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            /* Unchanged as shown, before trimming: a name that is not UTF-8 would get U+FFFD for good. */
            if (field.text.toString() == shown) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val to = field.text.toString().trim()
            if (to.isEmpty() || '/' in to || to == "." || to == "..") {
                field.error = getString(R.string.error_file_name)
                return@setOnClickListener
            }
            dialog.dismiss()
            val c = client() ?: return@setOnClickListener
            run({ c.rename(path(dir, e.name), path(dir, to)) }) { load(dir) }
        }
    }

    private fun delete(e: SftpEntry, dir: String) {
        client() ?: return
        AlertDialog.Builder(this)
            /* sftp-server tells a folder not empty as a mere failure. */
            .setMessage(getString(if (e.attrs.isDirectory) R.string.delete_folder else R.string.delete_file,
                shownName(e.name)))
            .setPositiveButton(R.string.delete) { _, _ ->
                val c = client() ?: return@setPositiveButton
                val p = path(dir, e.name)
                run({ if (e.attrs.isDirectory) c.rmdir(p) else c.remove(p) }) { load(dir) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* A folder typed in, from the one shown, ~ being the one the server started in. */
    private fun goTo() {
        client() ?: return
        val dir = cwd ?: return
        val shown = shownName(dir)
        val field = pathField(shown)
        AlertDialog.Builder(this)
            .setTitle(R.string.go_to)
            .setView(dialogLayout(field))
            .setPositiveButton(R.string.go) { _, _ ->
                /* Unchanged as shown: a name that is not UTF-8 would get U+FFFD for good. */
                if (field.text.toString() == shown) return@setPositiveButton
                val to = field.text.toString().trim()
                val start = home ?: dir
                when {
                    to.isEmpty() -> {}
                    to == "~" -> load(start)
                    to.startsWith("~/") -> load(path(start, to.substring(2)))
                    to.startsWith("/") -> load(to)
                    else -> load(path(dir, to))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        client() ?: return
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
            val c = client() ?: return@setOnClickListener
            /* Said rather than the failure sftp-server tells for it. */
            run({
                if (exists(c, path(dir, name))) throw IOException(getString(R.string.exists_already, name))
                c.mkdir(path(dir, name))
            }) { load(dir) }
        }
    }

    /*
     * Run task on the worker, then done with its result on the main
     * thread. Errors are shown, the browser stays on what it had.
     */
    private fun <T> run(task: () -> T, fail: (Throwable) -> Unit = ::failed, done: (T) -> Unit) {
        worker.execute {
            val result = try {
                Result.success(task())
            } catch (e: Exception) {
                if (!isFileError(e)) throw e
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(done, fail)
            }
        }
    }

    private fun failed(e: Throwable) {
        if (e is SftpCancelledException) return
        if (client() == null) {
            status.setText(R.string.files_not_connected)
            dropPending()
        }
        toast(getString(R.string.files_failed, e.message))
    }

    /*
     * A transfer with a progress dialog that can stop it, which task tells
     * with stopped(). It gives the size of the file it copies with sized(),
     * -1 when not known, the bar then only shows that it goes on.
     */
    private fun transfer(title: String,
        task: (progress: (Long) -> Boolean, stopped: () -> Boolean, sized: (Long) -> Unit) -> Unit, done: () -> Unit) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.isIndeterminate = true
        bar.max = PROGRESS_STEPS
        val count = TextView(this)
        /* Its own, a transfer slow to see it must not take the next one's. */
        val cancelled = AtomicBoolean()
        val size = AtomicLong(-1)
        /*
         * Hung up from the terminal screen, Close session there, it is
         * stopped too, but not by this screen going away, swiped from the
         * recent apps for one: cut then, it may be resumed.
         */
        val s = session
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogLayout(bar, count))
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            /*
             * Back while stopping hangs up a connection that may be
             * stalled. The browser stays: a download fails then, and can
             * still remove what it wrote, Android takes back the right
             * to the file once the screen is gone.
             */
            .setOnCancelListener { session?.close() }
            .show()
        dialog.setCanceledOnTouchOutside(false)
        /*
         * Shown until the transfer stops: the worker may wait on a stalled
         * connection, and what is asked next would queue behind it unseen.
         */
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelled.set(true)
            dialog.setTitle(R.string.stopping)
            it.isEnabled = false
            dialog.setCancelable(true)
        }
        progressDialog = dialog
        var shown = 0L
        run({
            task({ bytes ->
                val now = System.nanoTime()
                if (now - shown > PROGRESS_NANOS) {
                    shown = now
                    val total = size.get()
                    runOnUiThread { showProgress(bar, count, bytes, total) }
                }
                !cancelled.get()
            }, { cancelled.get() || s?.isClosed == true && !leaving }, { size.set(it) })
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

    /* A file growing meanwhile may send more than its size said. */
    private fun showProgress(bar: ProgressBar, count: TextView, bytes: Long, total: Long) {
        val done = Formatter.formatShortFileSize(this, bytes)
        if (total <= 0) {
            bar.isIndeterminate = true
            count.text = done
            return
        }
        bar.isIndeterminate = false
        bar.progress = (minOf(bytes, total) * PROGRESS_STEPS / total).toInt()
        count.text = getString(R.string.transferred, done, Formatter.formatShortFileSize(this, total))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_UPLOAD, 0, R.string.upload)
            .setIcon(R.drawable.ic_upload)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_NEW_FOLDER, 1, R.string.new_folder)
            .setIcon(R.drawable.ic_new_folder)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_GO_TO, 2, R.string.go_to)
        menu.add(Menu.NONE, MENU_REFRESH, 2, R.string.refresh)
        menu.add(Menu.NONE, MENU_HIDDEN, 2, R.string.show_hidden)
            .setCheckable(true)
            .setChecked(prefs.getBoolean(PREF_HIDDEN, false))
        menu.add(Menu.NONE, MENU_TERMINAL, 3, R.string.show_terminal)
        /* Back may only queue a listing a stalled or hostile server never answers. */
        menu.add(Menu.NONE, MENU_CLOSE, 4, R.string.close_session)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_UPLOAD -> if (client() != null) upload()
            MENU_NEW_FOLDER -> newFolder()
            MENU_GO_TO -> goTo()
            MENU_REFRESH -> cwd?.let { load(it) }
            MENU_HIDDEN -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean(PREF_HIDDEN, item.isChecked).apply()
                rearrange()
            }
            MENU_TERMINAL -> showTerminal(terminal.visibility != View.VISIBLE)
            MENU_CLOSE -> finish()
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
            listing = emptyList()
            entries = emptyList()
            adapter.items = emptyList()
            showTerminal(true)
            dropPending()
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
        private const val MENU_CLOSE = 5
        private const val MENU_GO_TO = 6
        private const val MENU_HIDDEN = 7
        private const val PREFS = "files"
        private const val PREF_HIDDEN = "hidden"
        private const val PROGRESS_NANOS = 200_000_000L
        private const val PROGRESS_STEPS = 1000
        private const val BUFFER_SIZE = 65536

        /* Line and paragraph separators, and the bidi controls. */
        private const val UNSHOWN = "\u2028\u2029\u061c\u200e\u200f" +
            "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069"

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
