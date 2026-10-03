package it.allard.rassh.config

/** A saved connection, stored as a Host block of ssh_config(5). */
data class Host(
    val name: String,
    val hostName: String = "",
    val user: String = "",
    val port: String = "",
    val identityFile: String = "",
    val localForwards: List<String> = emptyList(),
    val remoteForwards: List<String> = emptyList(),
    val dynamicForwards: List<String> = emptyList(),
    /** Any other line of the block, verbatim without indentation. */
    val other: List<String> = emptyList(),
) {
    companion object {
        /** A name usable as a single Host pattern without wildcards. */
        fun isValidName(name: String): Boolean =
            name.isNotEmpty() && name.none { it.isWhitespace() || it in "*?!,\"'#=" }

        /** A single word usable as a HostName or User value. */
        fun isValidWord(value: String): Boolean =
            value.none { it.isWhitespace() || it in "\"'#" }

        /** An option line that does not start a new block. */
        fun isValidOption(line: String): Boolean {
            /* ssh drops the quotes of a keyword, "Host" is Host. */
            val key = line.trim().split(' ', '\t', '=', limit = 2)[0].replace("\"", "")
            return !key.equals("Host", true) && !key.equals("Match", true)
        }

        fun isValidPort(port: String): Boolean =
            port.isEmpty() || port.length <= 5 && port.all { it in '0'..'9' } &&
                port.toInt() in 1..65535
    }
}

/**
 * A ssh_config file edited in place. Host blocks naming a single host
 * are exposed as Host entries, every other line is kept as is.
 */
class SshConfig private constructor(
    private val header: List<String>,
    private val blocks: MutableList<Block>,
) {
    /* leading holds the comment lines right above the Host or Match line. */
    private class Block(
        val leading: List<String>,
        val keyword: String,
        val value: String,
        val lines: List<String>,
    ) {
        val isHost: Boolean
            get() = keyword.equals("Host", ignoreCase = true) && Host.isValidName(value)
    }

    /*
     * One entry per name, from its first block: ssh takes the first value
     * it finds, edits change that block and remove() takes them all.
     */
    val hosts: List<Host>
        get() = blocks.filter { it.isHost }.distinctBy { it.value }.map { toHost(it) }

    fun find(name: String): Host? =
        blocks.find { it.isHost && it.value == name }?.let { toHost(it) }

    /**
     * Replace the block named old by host, or add host when old is null.
     * New blocks go before wildcard Host and Match blocks so that their
     * options take precedence.
     */
    fun put(old: String?, host: Host) {
        val i = if (old == null) -1 else blocks.indexOfFirst { it.isHost && it.value == old }
        if (i >= 0) {
            blocks[i] = Block(blocks[i].leading, "Host", host.name, render(host))
        } else {
            val at = blocks.indexOfFirst { !it.isHost }
            blocks.add(if (at < 0) blocks.size else at, Block(emptyList(), "Host", host.name, render(host)))
        }
    }

    fun remove(name: String) {
        blocks.removeAll { it.isHost && it.value == name }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (line in header) sb.append(line).append('\n')
        for (block in blocks) {
            for (line in block.leading) sb.append(line).append('\n')
            sb.append(block.keyword).append(' ').append(block.value).append('\n')
            for (line in block.lines) sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun toHost(block: Block): Host {
        var hostName: String? = null
        var user: String? = null
        var port: String? = null
        var identity: String? = null
        val local = mutableListOf<String>()
        val remote = mutableListOf<String>()
        val dynamic = mutableListOf<String>()
        val other = mutableListOf<String>()
        for (line in block.lines) {
            val (key, value) = split(line) ?: Pair("", "")
            when {
                key.equals("HostName", true) && hostName == null -> hostName = unquote(value)
                key.equals("User", true) && user == null -> user = unquote(value)
                key.equals("Port", true) && port == null -> port = unquote(value)
                key.equals("IdentityFile", true) && identity == null -> identity = unquote(value)
                key.equals("LocalForward", true) -> local.add(value)
                key.equals("RemoteForward", true) -> remote.add(value)
                key.equals("DynamicForward", true) -> dynamic.add(value)
                line.isNotBlank() -> other.add(line.trim())
            }
        }
        return Host(block.value, hostName.orEmpty(), user.orEmpty(), port.orEmpty(),
            identity.orEmpty(), local, remote, dynamic, other)
    }

    private fun render(host: Host): List<String> {
        val lines = mutableListOf<String>()
        fun add(key: String, value: String) {
            if (value.isNotEmpty()) lines.add("$INDENT$key $value")
        }
        add("HostName", host.hostName)
        add("User", host.user)
        add("Port", host.port)
        add("IdentityFile", quote(host.identityFile))
        host.localForwards.forEach { add("LocalForward", it) }
        host.remoteForwards.forEach { add("RemoteForward", it) }
        host.dynamicForwards.forEach { add("DynamicForward", it) }
        host.other.filter { it.isNotBlank() }.forEach { lines.add(INDENT + it.trim()) }
        lines.add("")
        return lines
    }

    companion object {
        private const val INDENT = "    "

        fun parse(text: String): SshConfig {
            val header = mutableListOf<String>()
            val blocks = mutableListOf<Block>()
            var leading = emptyList<String>()
            var keyword: String? = null
            var value = ""
            var lines = mutableListOf<String>()
            val all = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')
            for (line in all) {
                val kv = split(line)
                if (kv != null && (kv.first.equals("Host", true) || kv.first.equals("Match", true))) {
                    /* Comments right above a block describe it, not the one before. */
                    val above = if (keyword == null) header else lines
                    val comments = above.takeLastWhile { it.trim().startsWith("#") }
                    repeat(comments.size) { above.removeAt(above.size - 1) }
                    if (keyword != null) blocks.add(Block(leading, keyword, value, lines))
                    leading = comments
                    keyword = kv.first
                    value = kv.second
                    lines = mutableListOf()
                } else if (keyword == null) {
                    header.add(line)
                } else {
                    lines.add(line)
                }
            }
            if (keyword != null) blocks.add(Block(leading, keyword, value, lines))
            return SshConfig(header, blocks)
        }

        /**
         * Rewrite the IdentityFile lines naming a key of paths, given as
         * written in the file, to the path it maps to.
         */
        fun replaceIdentities(text: String, paths: Map<String, String>): String {
            if (text.isEmpty()) return text
            val lines = text.removeSuffix("\n").split('\n').map { line ->
                val kv = split(line)
                val to = kv?.let { paths[unquote(it.second)] }
                if (kv == null || to == null || !kv.first.equals("IdentityFile", true)) {
                    line
                } else {
                    line.takeWhile { it.isWhitespace() } + kv.first + " " + quote(to)
                }
            }
            return lines.joinToString("\n") + if (text.endsWith("\n")) "\n" else ""
        }

        /** Split a line in keyword and arguments, null for comments and blank lines. */
        private fun split(line: String): Pair<String, String>? {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) return null
            val end = s.indexOfFirst { it.isWhitespace() || it == '=' }
            if (end < 0) return Pair(s, "")
            var rest = s.substring(end).trimStart()
            if (rest.startsWith("=")) rest = rest.substring(1).trimStart()
            return Pair(s.substring(0, end), rest)
        }

        private fun unquote(s: String): String =
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

        private fun quote(s: String): String =
            if (s.any { it.isWhitespace() }) "\"" + s + "\"" else s
    }
}
