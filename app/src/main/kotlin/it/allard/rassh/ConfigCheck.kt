package it.allard.rassh

import android.content.Context
import it.allard.rassh.config.SshConfig
import it.allard.rassh.config.lowerAscii
import java.io.File
import java.io.IOException

/*
 * A name no Host line lists, for the destinations of quick connect and
 * the blocks of patterns: .invalid never names a real host.
 */
private const val UNLISTED = "unlisted.invalid"

/* The names ssh must take config for, first ones first: those its Host lines list and one they do not. */
fun checkedNames(config: SshConfig, first: List<String>): List<String> =
    (first + config.names + UNLISTED).distinct()

/* What to tell of a refusal from configRefusal(). */
fun Context.refusedMessage(refusal: Pair<String, String>): String {
    val name = if (refusal.first == UNLISTED) getString(R.string.config_unlisted) else refusal.first
    return getString(R.string.config_refused, name, refusal.second)
}

/* use called with a file holding text, its own: a check left running by a recreated screen may still use another. */
@Throws(IOException::class)
private fun <T> withConfigFile(paths: Paths, text: String, use: (File) -> T): T {
    val file = File.createTempFile("config", ".check", paths.tmp)
    try {
        paths.writePrivate(file, text)
        return use(file)
    } finally {
        file.delete()
    }
}

/* The output of ssh -G for name with file as its config, and its exit status. */
@Throws(IOException::class)
private fun dump(paths: Paths, file: File, name: String): Pair<String, Int> {
    /* Not looking the name up: offline, a canonicalized name would fail the check. */
    val argv = listOf("ssh", "-G", "-F", file.path, "-o", "CanonicalizeHostname=no",
        "-o", "CanonicalizePermittedCNAMEs=none", name)
    val (out, status) = execute(paths.ssh, argv, paths.env, paths.home.path)
    return Pair(out.replace(file.path, "config"), status)
}

/* The same with text as the config. */
@Throws(IOException::class)
fun dumpConfig(paths: Paths, text: String, name: String): Pair<String, Int> =
    withConfigFile(paths, text) { dump(paths, it, name) }

/*
 * The first of names ssh refuses text as its configuration for, with
 * what it says, null when it takes it for all. One bad line makes ssh
 * refuse the whole file, every host would then fail, and HostName or
 * User expand % tokens. ssh reads the lines of every block, an option
 * it does not know passes only after an IgnoreUnknown line naming it
 * applied to the host: one host may take an option the others fail on.
 * A name refused in before, the config the change was made to, is not
 * the change's doing, unless among own, the names it adds or edits: a
 * line written in the local shell must not stop every other change.
 */
@Throws(IOException::class)
fun configRefusal(paths: Paths, text: String, names: List<String>, before: String,
    own: List<String>): Pair<String, String>? =
    withConfigFile(paths, text) { file ->
        names.firstNotNullOfOrNull { name ->
            val (out, status) = dump(paths, file, name)
            val refused = status != 0 && (name in own || dumpConfig(paths, before, name).second == 0)
            if (refused) Pair(name, out) else null
        }
    }

/*
 * The names ssh reaches the hosts of names, patterns too, by with text
 * as its config: their HostName, by which it reads the file again after
 * a Match final or canonicalization, and their ProxyJump hosts, whose
 * own ssh reads it for them. A block of one of those would change these
 * hosts. Null when a ProxyJump value may reach any name, see jumpNames().
 */
@Throws(IOException::class)
fun reachedNames(paths: Paths, text: String, names: List<String>): Set<String>? =
    withConfigFile(paths, text) { file ->
        names.flatMap { name ->
            /* Lines end in line feeds only, see HostKeys.knownHost(). */
            dump(paths, file, name).first.split('\n').flatMap { line ->
                val (key, value) = line.trim().split(' ', limit = 2).takeIf { it.size == 2 }
                    ?: return@flatMap emptyList()
                when (key) {
                    "hostname" -> listOf(value)
                    "proxyjump" -> jumpNames(value) ?: return@withConfigFile null
                    else -> emptyList()
                }
            }
        }.filter { it.isNotEmpty() && it != "none" }.toSet()
    }

/*
 * The other hosts of text, as their names and patterns are, and one no
 * Host line names, whose ProxyJump goes through the host name: renamed
 * or deleted, ssh would take name for a host name there, without its
 * block. ssh -G writes a hop as [user@]host[:port], an IPv6 address in
 * brackets, the last one turned so from an ssh:// URI, the others as
 * they were written, blanks around commas too, with the quotes and
 * backslashes the shell drops and the trailing dot ssh drops from a URI.
 * Its Host lines match names as typed, and lowercased in a final pass,
 * which Match final or CanonicalizeHostname bring: a hop is taken as
 * typed or lowercased.
 */
@Throws(IOException::class)
fun jumpingThrough(paths: Paths, text: String, name: String): List<String> =
    withConfigFile(paths, text) { file ->
        val config = SshConfig.parse(text)
        (checkedNames(config, emptyList()) + config.patterns).filter { it != name }.filter { other ->
            dump(paths, file, other).first.split('\n').any { line ->
                val (key, value) = line.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: return@any false
                key == "proxyjump" && value.split(',').any { hop ->
                    val written = hop.trim().filter { it !in "\"'\\" }
                    val host = written.removePrefix("ssh://").substringAfterLast('@').substringBefore('/')
                    val bare = if (host.startsWith("[")) host.substringAfter('[').substringBefore(']')
                        else host.substringBefore(':')
                    val hostName = if (written.startsWith("ssh://")) bare.removeSuffix(".") else bare
                    hostName == name || lowerAscii(hostName) == name
                }
            }
        }
    }

/* Hosts as jumpingThrough() gives them, for a message. */
fun Context.shownHosts(names: List<String>): String =
    names.joinToString(", ") { if (it == UNLISTED) getString(R.string.config_unlisted) else it }

private val JUMP_SEPARATORS = Regex("""[\s,;&|<>()`$@:/\[\]]+""")
private val BRACKETED = Regex("""\[([^\]]*)]""")
private const val EXPANDED = "$`~*?{}"

/*
 * The names a ProxyJump value may reach. ssh puts the hops in the
 * ProxyCommand it makes of them, which a shell runs, dropping quotes and
 * backslashes and splitting words, after parsing the last one itself, a
 * URI losing a trailing dot. So every word of it is taken, also a user,
 * a port or a scheme, and what is in brackets, an IPv6 address for one,
 * with and without a trailing dot: a block may be skipped for nothing,
 * none taken that ssh reads for a jump host but through % escapes or
 * tokens. Null when the shell would expand a word, brackets aside as
 * they hold IPv6 addresses, or ssh take one for an option: any name may
 * then be reached.
 */
private fun jumpNames(value: String): List<String>? {
    val words = value.filter { it !in "\"'\\" }
    val split = words.split(JUMP_SEPARATORS)
    if (words.any { it in EXPANDED } || split.any { it.startsWith("-") }) return null
    return (split + BRACKETED.findAll(words).map { it.groupValues[1] }).flatMap { listOf(it, it.removeSuffix(".")) }
}
