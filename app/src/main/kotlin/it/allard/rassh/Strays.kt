package it.allard.rassh

import android.app.Activity
import android.app.AlertDialog
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/* The longest endStrays() waits for the processes it killed, in milliseconds. */
private const val END_WAIT = 1000L

/* The longest command line shown for a stray. */
private const val COMMAND_MAX = 100

/**
 * A process of the app left running outside the sessions, as one put in
 * the background in the local shell. Running as the app, it can read
 * the keys withAgent() loads, from the pipes of the shell or from the
 * memory of ssh-add. start tells it from a later process given the same
 * pid.
 */
class Stray(val pid: Int, val start: Long, val command: String)

/* What is used of /proc/<pid>/stat, see proc(5). */
private class Stat(val name: String, val state: Char, val ppid: Int, val pgrp: Int, val session: Int,
    val tpgid: Int, val start: Long)

private fun stat(pid: Int): Stat? {
    val text = try {
        File("/proc/$pid/stat").readText()
    } catch (_: IOException) {
        return null
    }
    /* The command name may hold spaces and parentheses, the last one ends it. */
    val start = text.indexOf('(')
    val end = text.lastIndexOf(')')
    if (start < 0 || end < start || end + 2 > text.length) return null
    val f = text.substring(end + 2).split(' ')
    return try {
        Stat(text.substring(start + 1, end), f[0][0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[5].toInt(),
            f[19].toLong())
    } catch (_: NumberFormatException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

private fun owner(pid: Int): Int = try {
    Os.stat("/proc/$pid").st_uid
} catch (_: ErrnoException) {
    -1
}

/* Its command line, or its name when it has none to show. */
private fun command(pid: Int, s: Stat): String {
    val line = try {
        File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).trimEnd('\u0000').replace('\u0000', ' ')
    } catch (_: IOException) {
        ""
    }.ifBlank { s.name }
    return if (line.length > COMMAND_MAX) line.take(COMMAND_MAX) + "..." else line
}

/*
 * The processes of the app, the app aside, that are not in the
 * foreground of a session it started. Each session is a session in the
 * kernel too, led by the program the app started, see native/pty.c.
 * What the program runs in the foreground stays in the group the
 * terminal reads for, what a shell puts in the background, or a program
 * becoming a daemon, as ssh -f does, leaves it.
 */
fun strays(): List<Stray> {
    val me = Os.getpid()
    val uid = Os.getuid()
    val stats = File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }
        .filter { it != me && owner(it) == uid }
        .mapNotNull { pid -> stat(pid)?.let { pid to it } }.toMap()
    fun inSession(pid: Int, s: Stat): Boolean {
        val leader = stats[s.session] ?: return false
        return leader.ppid == me && leader.state != 'Z' && (pid == s.session || s.pgrp == s.tpgid)
    }
    return stats.filter { (pid, s) -> s.state != 'Z' && !inSession(pid, s) }
        .map { (pid, s) -> Stray(pid, s.start, command(pid, s)) }
}

/* Kill the strays still running, waiting a moment for them to die. */
fun endStrays(strays: List<Stray>) {
    fun alive(s: Stray) = stat(s.pid)?.let { it.start == s.start && it.state != 'Z' } == true
    val killed = strays.filter { alive(it) }
    for (s in killed) {
        try {
            Os.kill(s.pid, OsConstants.SIGKILL)
        } catch (_: ErrnoException) {
        }
    }
    val until = System.currentTimeMillis() + END_WAIT
    while (killed.any { alive(it) } && System.currentTimeMillis() < until) Thread.sleep(10)
}

/*
 * Call then once the strays are ended, or kept by the user. Closing the
 * question with Back calls nothing.
 */
fun Activity.withoutStrays(then: () -> Unit) {
    val strays = strays()
    if (strays.isEmpty()) return then()
    AlertDialog.Builder(this)
        .setTitle(R.string.strays_title)
        .setMessage(getString(R.string.strays_message, strays.joinToString("\n") { "${it.pid} ${it.command}" }))
        /* Asked again for any that started meanwhile or would not die. */
        .setPositiveButton(R.string.strays_end) { _, _ ->
            endStrays(strays)
            withoutStrays(then)
        }
        .setNegativeButton(R.string.strays_keep) { _, _ -> then() }
        .show()
}
