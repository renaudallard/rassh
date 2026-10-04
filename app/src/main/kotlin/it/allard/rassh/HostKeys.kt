package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/* Replacing the known key of a host whose key changed. */

/* The name and port a known_hosts line has for the target, as ssh -G resolves them. */
private class KnownHost(val name: String, val files: List<File>)

/*
 * Where ssh looks up the key of the host target reaches, taken from the
 * local configuration only: what the server printed cannot choose which
 * keys are removed. Only files under ~/.ssh are considered.
 */
@Throws(IOException::class)
private fun knownHost(paths: Paths, target: List<String>): KnownHost {
    val config = runProgram(paths.ssh, listOf("ssh", "-G") + target, paths.env, paths.home.path)
        .lines().mapNotNull { line ->
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
    return KnownHost(name, files)
}

/*
 * The lines of file holding a key of name, hashed or not. ssh-keygen
 * lists @cert-authority and @revoked lines with CA or REVOKED after the
 * number, they are kept, as ssh-keygen -R does. Kept as text, the
 * numbers change with any other removal before the user decides.
 */
@Throws(IOException::class)
private fun keyLines(paths: Paths, name: String, file: File): Set<String> {
    val out = runProgram(paths.keygen, listOf("ssh-keygen", "-F", name, "-f", file.path), paths.env, paths.home.path)
    val lines = file.readText().split('\n')
    return FOUND.findAll(out).mapNotNull { lines.getOrNull(it.groupValues[1].toInt() - 1) }.toSet()
}

private val FOUND = Regex("""^# Host .* found: line (\d+)[ \t]*$""", RegexOption.MULTILINE)

/*
 * Remove the lines of file found in gone. ssh-keygen -R cannot, it keeps
 * a backup with link(), which Android refuses to apps.
 */
@Throws(IOException::class)
private fun removeLines(paths: Paths, file: File, gone: Set<String>) {
    val kept = paths.textOf(file).split('\n').filter { it !in gone }
    paths.writePrivate(file, kept.joinToString("\n"))
}

/**
 * When ssh refused session as the host key changed, warn and offer to
 * remove the known key. Connecting again then shows the new key to
 * confirm, as for a new host.
 */
fun Activity.offerNewHostKey(paths: Paths, session: Session) {
    val changed = session.changedHostKey ?: return
    if (session.hostKeyOffered) return
    val target = session.target ?: return
    session.hostKeyOffered = true
    thread(name = "host-key") {
        val found = try {
            val known = knownHost(paths, target)
            known.name to known.files.associateWith { keyLines(paths, known.name, it) }.filterValues { it.isNotEmpty() }
        } catch (e: IOException) {
            runOnUiThread { toast(getString(R.string.host_key_failed, e.message)) }
            return@thread
        }
        runOnUiThread {
            /* A jump host's key may be the one refused, never offer to drop another. */
            if (isDestroyed || found.first != changed || found.second.isEmpty()) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle(R.string.host_key_changed)
                .setMessage(getString(R.string.host_key_changed_text, found.first))
                .setPositiveButton(R.string.host_key_remove) { _, _ ->
                    try {
                        for ((file, lines) in found.second) removeLines(paths, file, lines)
                    } catch (e: IOException) {
                        toast(getString(R.string.host_key_remove_failed, e.message))
                        return@setPositiveButton
                    }
                    toast(getString(R.string.host_key_removed))
                }
                .setNegativeButton(R.string.host_key_keep, null)
                .show()
        }
    }
}
