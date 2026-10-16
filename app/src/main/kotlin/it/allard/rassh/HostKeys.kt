package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/* Replacing the known key of a host whose key changed. */

/*
 * The name a known_hosts line has for the target, as [host]:port off
 * port 22, as ssh -G resolves it, and the files ssh looks in. quiet when
 * the LogLevel is below INFO: ssh then does not end a session with a
 * terminal with "Connection to host closed.", which tells its own last
 * lines from the same ones printed by the server.
 */
private class KnownHost(val name: String, val files: List<File>, val quiet: Boolean, val checking: String?)

/*
 * Where ssh looks up the key of the host target reaches, taken from the
 * local configuration only: what the server printed cannot choose which
 * keys are removed. Only files under ~/.ssh are considered.
 */
@Throws(IOException::class)
private fun knownHost(paths: Paths, target: List<String>): KnownHost {
    val (out, status) = execute(paths.ssh, listOf("ssh", "-G") + target, paths.env, paths.home.path)
    /* A config edited since, in the local shell for instance, may be refused: ssh says why. */
    if (status != 0) throw IOException(out.trim().ifEmpty { "ssh -G: exit $status" })
    /* Lines end in line feeds only: a value may hold a carriage return, which must not start a line. */
    val config = out.split('\n').mapNotNull { line ->
        val i = line.indexOf(' ')
        if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
    }.toMap()
    /* An alias is taken as it is, the port only goes with the host name, see sshconnect.c. */
    val alias = config["hostkeyalias"]
    val port = config["port"] ?: "22"
    val name = if (alias != null && alias != "none") {
        alias
    } else {
        val host = config["hostname"] ?: throw IOException("no host name")
        if (port == "22") host else "[$host]:$port"
    }
    val dir = paths.sshDir.canonicalFile
    val files = config["userknownhostsfile"].orEmpty().split(' ').filter { it.isNotEmpty() }
        .map { File(it).canonicalFile }
        .filter { it.parentFile == dir && it.isFile }
    return KnownHost(name, files, config["loglevel"]?.uppercase() in setOf("QUIET", "FATAL", "ERROR"),
        config["stricthostkeychecking"])
}

/*
 * The lines of file holding a key of name, hashed or not. ssh-keygen
 * lists @cert-authority and @revoked lines with CA or REVOKED after the
 * number, they are kept, as ssh-keygen -R does. Kept as text, the
 * numbers change with any other removal before the user decides. The
 * text is the line ssh-keygen prints under each number, as the file may
 * change in between, compared without the trailing blanks it may lose.
 */
@Throws(IOException::class)
private fun keyLines(paths: Paths, name: String, file: File): Set<String> {
    val (text, status) = execute(paths.keygen, listOf("ssh-keygen", "-F", name, "-f", file.path), paths.env, paths.home.path)
    /* 1 when nothing was found. */
    if (status != 0 && status != 1) throw IOException(text.trim().ifEmpty { "ssh-keygen -F: exit $status" })
    val out = text.split('\n')
    val found = out.indices.filter { FOUND.matches(out[it]) }.mapNotNull { out.getOrNull(it + 1)?.trimEnd() }.toSet()
    return Paths.bytesOf(file).toString(Charsets.UTF_8).split('\n').filter { it.trimEnd() in found }.toSet()
}

/*
 * What becomes of line of known_hosts once the key of host goes: null
 * when it names host alone, in any case as ssh compares them, or hashed,
 * which names one, the line without host when it names other hosts too,
 * and line itself for a pattern, which other hosts may match.
 */
private fun withoutHost(line: String, host: String): String? {
    val start = line.indexOfFirst { it != ' ' && it != '\t' }
    if (start < 0) return line
    val end = line.indexOfAny(charArrayOf(' ', '\t'), start).let { if (it < 0) line.length else it }
    val field = line.substring(start, end)
    if (field.startsWith("|1|")) return null
    if (field.any { it in "*?!" }) return line
    val names = field.split(',').filter { !it.equals(host, ignoreCase = true) }
    if (names.all { it.isEmpty() }) return null
    return line.substring(0, start) + names.joinToString(",") + line.substring(end)
}

private val FOUND = Regex("""# Host .* found: line \d+[ \t]*""")

/*
 * Replace the lines of file found in edits with their value, remove
 * them when null. ssh-keygen -R cannot, it keeps a backup with link(),
 * which Android refuses to apps, and it would remove whole lines.
 */
@Throws(IOException::class)
private fun editLines(paths: Paths, file: File, edits: Map<String, String?>) {
    val kept = paths.textOf(file).split('\n').mapNotNull { if (it in edits) edits[it] else it }
    paths.writeText(file, kept.joinToString("\n"))
}

/**
 * When ssh refused session as the host key changed, warn and offer to
 * remove the known key. Connecting again then shows the new key to
 * confirm, as for a new host, unless StrictHostKeyChecking makes ssh
 * take it or refuse it.
 */
fun Activity.offerNewHostKey(paths: Paths, session: Session) {
    val changed = session.changedHostKey ?: return
    if (session.hostKeyOffered) return
    val target = session.target ?: return
    session.hostKeyOffered = true
    thread(name = "host-key") {
        val found = try {
            val known = knownHost(paths, target)
            /* Not offered when the server may have printed what ssh would. */
            if (known.quiet) return@thread
            Triple(known.name, known.files.associateWith { keyLines(paths, known.name, it) }.filterValues { it.isNotEmpty() },
                known.checking)
        } catch (e: IOException) {
            runOnUiThread { toast(getString(R.string.host_key_failed, e.message)) }
            return@thread
        }
        /*
         * A screen gone first, recreated on a theme change, leaves it to
         * the next one, which may already listen to the session.
         */
        fun offerAgain() {
            session.hostKeyOffered = false
            session.listener?.onUpdate()
        }
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread offerAgain()
            /* A jump host's key may be the one refused, never offer to drop another. */
            if (found.first != changed || found.second.isEmpty()) return@runOnUiThread
            /* A pattern is left, it would take the keys of other hosts with it. */
            val edits = found.second.mapValues { (_, lines) ->
                lines.associateWith { withoutHost(it, found.first) }.filter { (line, edit) -> edit != line }
            }.filterValues { it.isNotEmpty() }
            val left = found.second.values.flatten().any { withoutHost(it, found.first) == it }
            if (edits.isEmpty()) return@runOnUiThread toast(getString(R.string.host_key_shared, found.first))
            /* What the next connection does once the key is gone: ssh prompts only when asked to. */
            val (then, removed) = when (found.third) {
                "accept-new", "false" -> R.string.host_key_then_accepted to R.string.host_key_removed_accepted
                "true" -> R.string.host_key_then_refused to R.string.host_key_removed_refused
                else -> R.string.host_key_then_asked to R.string.host_key_removed
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.host_key_changed)
                .setMessage(getString(R.string.host_key_changed_text, found.first, getString(then)))
                .setPositiveButton(R.string.host_key_remove) { _, _ ->
                    try {
                        for ((file, lines) in edits) editLines(paths, file, lines)
                    } catch (e: IOException) {
                        toast(getString(R.string.host_key_remove_failed, e.message))
                        return@setPositiveButton
                    }
                    toast(if (left) getString(R.string.host_key_removed_shared, found.first) else getString(removed))
                }
                .setNegativeButton(R.string.host_key_keep, null)
                .show()
            dismissWithScreen(dialog) { if (isDestroyed) offerAgain() }
        }
    }
}
