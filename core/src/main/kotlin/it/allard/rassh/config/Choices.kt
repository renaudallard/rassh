package it.allard.rassh.config

/** An ssh_config option offered with a few values, written as ssh_config(5) spells them. */
class Choice(val keyword: String, val values: List<String>)

/**
 * Options often set for a host, edited apart from its other lines. A
 * choice left unset writes no line, so that the host keeps what Host *
 * blocks or ssh's own defaults give it.
 */
object Choices {
    val ALL = listOf(
        Choice("ServerAliveInterval", listOf("0", "15", "30", "60")),
        Choice("Compression", listOf("yes", "no")),
        Choice("StrictHostKeyChecking", listOf("ask", "accept-new", "yes", "no")),
        Choice("UpdateHostKeys", listOf("yes", "ask", "no")),
        Choice("ConnectTimeout", listOf("5", "10", "30")),
        Choice("TCPKeepAlive", listOf("yes", "no")),
        Choice("AddressFamily", listOf("any", "inet", "inet6")),
        Choice("ExitOnForwardFailure", listOf("yes", "no")),
        /* Not QUIET nor FATAL, they hide the error the app reads at a changed host key. */
        Choice("LogLevel", listOf("ERROR", "INFO", "VERBOSE", "DEBUG", "DEBUG2", "DEBUG3")),
    )

    /** The choice for the keyword of line, or null. */
    fun of(line: String): Choice? {
        val key = SshConfig.keyword(line)?.first ?: return null
        return ALL.find { it.keyword.equals(key, true) }
    }

    fun line(choice: Choice, value: String): String = "${choice.keyword} $value"

    /**
     * Split the other lines of a host into the values they set and the
     * rest. A line is taken when it is the only one of its keyword and
     * its value one of the choice's. A comment or quotes keep it with
     * the rest, as does a keyword set twice.
     */
    fun take(lines: List<String>): Pair<Map<Choice, String>, List<String>> {
        val taken = mutableMapOf<Choice, String>()
        val rest = mutableListOf<String>()
        for (line in lines) {
            val choice = of(line)
            val value = choice?.let { c -> c.values.find { it.equals(SshConfig.keyword(line)?.second, true) } }
            if (choice != null && value != null && lines.count { of(it) == choice } == 1)
                taken[choice] = value
            else
                rest.add(line)
        }
        return Pair(taken, rest)
    }

    /**
     * The values of the choices in the output of ssh -G, which writes
     * the flags it holds as true and false.
     */
    fun effective(dump: String): Map<Choice, String> {
        val values = mutableMapOf<Choice, String>()
        for (line in dump.lines()) {
            val (key, value) = line.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: continue
            val choice = ALL.find { it.keyword.equals(key, true) } ?: continue
            values[choice] = when (value) {
                "true" -> "yes"
                "false" -> "no"
                else -> value
            }
        }
        return values
    }
}
