package it.allard.rassh

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * A transfer of the file browser cut short with its connection, kept by
 * host until it is resumed or dropped, once the browser is connected
 * there again. The app keeps its right to the files on the phone, as the
 * picker only grants it to the screen that asked.
 */
sealed class Resumable {
    abstract val uris: List<Uri>

    /* A download needs to write to its file, an upload only reads. */
    abstract val write: Boolean

    /*
     * remote downloaded into uri, which holds its start. size and mtime
     * are those of remote then: changed, it is another file.
     */
    class Download(val remote: String, val uri: Uri, val size: Long, val mtime: Long?) : Resumable() {
        override val uris: List<Uri>
            get() = listOf(uri)
        override val write: Boolean
            get() = true
    }

    /*
     * uris uploaded as names into dir, done up to index. The file there
     * was length bytes long and last modified at modified, in ns, when
     * the server had started to take it, length is -1 when it had not.
     * sha256 is the hash of its first sent bytes, those read when cut.
     */
    class Upload(val dir: String, override val uris: List<Uri>, val names: List<String>, val index: Int,
        val length: Long, val modified: Long, val sent: Long, val sha256: String) : Resumable() {
        override val write: Boolean
            get() = false
    }
}

private const val PREFS = "resume"

/* Two browsers of a host may lose their connection at once. */
private val lock = Any()

/*
 * Server names are kept as their UTF-16 units in hex: one not in UTF-8
 * holds lone surrogates, see decodeName(), which the preferences file
 * would write as "?".
 */
private fun units(s: String): String = s.map { it.code.toString(16).padStart(4, '0') }.joinToString("")

private fun fromUnits(hex: String): String {
    if (hex.length % 4 != 0) throw NumberFormatException(hex)
    return hex.chunked(4).joinToString("") { it.toInt(16).toChar().toString() }
}

/** The transfer to host cut short, if any. */
fun Context.resumable(host: String): Resumable? {
    val text = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(host, null) ?: return null
    return try {
        val o = JSONObject(text)
        if (o.has("remote")) {
            Resumable.Download(fromUnits(o.getString("remote")), Uri.parse(o.getString("uri")), o.getLong("size"),
                if (o.has("mtime")) o.getLong("mtime") else null)
        } else {
            val uris = o.getJSONArray("uris")
            val names = o.getJSONArray("names")
            if (uris.length() != names.length()) return null
            Resumable.Upload(fromUnits(o.getString("dir")),
                (0 until uris.length()).map { Uri.parse(uris.getString(it)) },
                (0 until names.length()).map { fromUnits(names.getString(it)) }, o.getInt("index"),
                o.getLong("length"), o.getLong("modified"), o.getLong("sent"), o.getString("sha256"))
        }
    } catch (_: JSONException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

private fun Resumable.json(): String {
    val o = JSONObject()
    when (this) {
        is Resumable.Download -> {
            o.put("remote", units(remote)).put("uri", uri.toString()).put("size", size)
            mtime?.let { o.put("mtime", it) }
        }
        is Resumable.Upload -> {
            o.put("dir", units(dir)).put("uris", JSONArray(uris.map { it.toString() }))
                .put("names", JSONArray(names.map { units(it) })).put("index", index).put("length", length)
                .put("modified", modified).put("sent", sent).put("sha256", sha256)
        }
    }
    return o.toString()
}

/*
 * Keep r, the transfer to host cut short, in place of an earlier one,
 * whose partial download goes and whose files are let go, see letGo().
 */
fun Context.keepResumable(host: String, r: Resumable) {
    val old = synchronized(lock) {
        val old = resumable(host)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(host, r.json()).apply()
        old
    } ?: return
    if (old is Resumable.Download && old.uri !in r.uris) deleteDocument(old.uri)
    letGo(old.uris, old.write)
}

/* Forget the transfer to host, letting go of its files, only if it is still r when given. */
fun Context.forgetResumable(host: String, r: Resumable? = null) {
    val old = synchronized(lock) {
        val old = resumable(host)
        if (old == null || r != null && old.json() != r.json()) return
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(host).apply()
        old
    }
    letGo(old.uris, old.write)
}

/*
 * Forget the downloads cut short into uri but except, without deleting
 * it nor letting it go: a download about to write it is in its hands.
 * Kept, Discard would delete a file it completed.
 */
fun Context.forgetDownloadsInto(uri: Uri, except: Resumable?) {
    synchronized(lock) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val gone = prefs.all.keys.filter { host ->
            val r = resumable(host)
            r is Resumable.Download && r.uri == uri && (except == null || r.json() != except.json())
        }
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach { remove(it) } }.apply()
    }
}

/** Whether r is still the transfer to host cut short, not dropped since it was read. */
fun Context.isKept(host: String, r: Resumable): Boolean = resumable(host)?.json() == r.json()

/* Drop the transfer to host, a partial download with it. A provider may be slow, not on the UI thread. */
fun Context.dropResumable(host: String) {
    val r = resumable(host) ?: return
    if (r is Resumable.Download) deleteDocument(r.uri)
    forgetResumable(host, r)
}

/* Keep the right to uris past the screen, false when a provider does not let it. */
fun Context.keepAccess(uris: List<Uri>, write: Boolean): Boolean {
    /* Those kept already, for a transfer cut short, are not to be let go here. */
    val held = contentResolver.persistedUriPermissions.map { it.uri }.toSet()
    val kept = mutableListOf<Uri>()
    for (uri in uris) {
        try {
            contentResolver.takePersistableUriPermission(uri, accessFlags(write))
        } catch (_: SecurityException) {
            releaseAccess(kept.filter { it !in held }, write)
            return false
        }
        kept.add(uri)
    }
    return true
}

/*
 * Let go of uris but for those a transfer cut short to any host still
 * needs: the app holds one right to a file, however many keep it.
 */
fun Context.letGo(uris: List<Uri>, write: Boolean) {
    synchronized(lock) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val needed = prefs.all.keys.flatMap { resumable(it)?.uris.orEmpty() }.toSet()
        releaseAccess(uris.filter { it !in needed }, write)
    }
}

fun Context.releaseAccess(uris: List<Uri>, write: Boolean) {
    for (uri in uris) {
        try {
            contentResolver.releasePersistableUriPermission(uri, accessFlags(write))
        } catch (_: SecurityException) {
        }
    }
}

private fun accessFlags(write: Boolean): Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
