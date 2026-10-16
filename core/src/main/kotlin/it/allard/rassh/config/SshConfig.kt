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
    /** Any other line of the block, verbatim, without indentation unless ssh needs it, see SshConfig.trimOption(). */
    val other: List<String> = emptyList(),
) {
    companion object {
        /**
         * A name usable as a single Host pattern without wildcards, and by
         * ssh as a destination: ssh_valid_hostname() in readconf.c refuses
         * a leading - and these characters, a - would also make an option.
         * ssh reads a destination with @ as user@host and one starting with
         * ssh:// as a URI, before it looks for the Host block. sftp and
         * scp take the host out of [ ], which a name thus cannot hold.
         */
        fun isValidName(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith("-") &&
                name.none { it.isWhitespace() || it.isISOControl() || it in "*?!,\"'#=`$\\;&<>|(){}@/[]" }

        /** A single word usable as a HostName value. */
        fun isValidWord(value: String): Boolean =
            value.none { it.isWhitespace() || it in "\"'#" }

        /*
         * A User value. ssh takes one from the config as written, spaces
         * and quotes included, which are quoted when saved: only a line
         * break, or another control character, could not be.
         */
        fun isValidUser(value: String): Boolean = value.none { it.isISOControl() }

        /** An option line that does not start a new block. */
        fun isValidOption(line: String): Boolean {
            val key = SshConfig.keyword(line)?.first ?: return true
            return !key.equals("Host", true) && !key.equals("Match", true)
        }

        /* A port number, leading zeros allowed as ssh reads it with strtonum(). */
        fun isValidPort(port: String): Boolean =
            port.isEmpty() || port.all { it in '0'..'9' } && port.trimStart('0').length <= 5 &&
                port.toInt() in 1..65535
    }
}

/**
 * A ssh_config file edited in place. Host blocks naming a single host
 * are exposed as Host entries, every other line is kept as is.
 */
class SshConfig private constructor(
    private var header: List<String>,
    private val blocks: MutableList<Block>,
    private val footer: List<String>,
) {
    /*
     * leading holds the comment lines right above the Host or Match line,
     * line that line as read, null for a block put() wrote.
     */
    private class Block(
        val leading: List<String>,
        val keyword: String,
        val value: String,
        val lines: List<String>,
        val line: String? = null,
    ) {
        /* The one host a Host line names, a comment after it allowed, or null. */
        val name: String? =
            if (!keyword.equals("Host", ignoreCase = true)) null
            else arguments(value)?.singleOrNull()?.takeIf { Host.isValidName(it) }

        val isHost: Boolean
            get() = name != null
    }

    /*
     * One entry per name, from its first block: ssh takes the first value
     * it finds, edits change that block and remove() takes them all.
     */
    val hosts: List<Host>
        get() = blocks.filter { it.isHost }.distinctBy { it.name }.map { toHost(it) }

    /** Every host name the Host lines list, alone or with others, patterns and negations left out. */
    val names: List<String>
        get() = blocks.filter { it.keyword.equals("Host", ignoreCase = true) }
            .flatMap { arguments(it.value).orEmpty() }.filter { Host.isValidName(it) }.distinct()

    fun find(name: String): Host? =
        blocks.find { it.name == name }?.let { toHost(it) }

    /**
     * Whether a Host line names name, alone or with others, in any case:
     * ssh matches Host patterns case-sensitively, but host names are not,
     * as in DNS, so Web and web cannot be two hosts. ssh takes the first
     * value it finds, a new block for name would change that host. The
     * blocks of except, the ones put() renames when renaming it, do not
     * count.
     */
    fun isUsed(name: String, except: String? = null): Boolean =
        blocks.any { b ->
            (except == null || b.name != except) && b.keyword.equals("Host", ignoreCase = true) &&
                arguments(b.value).orEmpty().any { it.equals(name, ignoreCase = true) }
        }

    /**
     * Replace the block named old by host, or add host when old is null.
     * New blocks go before wildcard Host and Match blocks so that their
     * options take precedence.
     */
    fun put(old: String?, host: Host) {
        val i = if (old == null) -1 else blocks.indexOfFirst { it.name == old }
        if (i >= 0) {
            /* The Host line stays as written while the name does, with its comment. */
            val line = if (host.name == old) blocks[i].line else renamed(blocks[i], host.name)
            blocks[i] = Block(blocks[i].leading, "Host", host.name, render(host), line)
            /* Later blocks of the old name are renamed too, as remove() takes them all. */
            if (host.name != old) {
                for (j in i + 1 until blocks.size) {
                    val b = blocks[j]
                    if (b.name == old) blocks[j] = Block(b.leading, b.keyword, host.name, b.lines, renamed(b, host.name))
                }
            }
        } else {
            val at = blocks.indexOfFirst { !it.isHost }.let { if (it < 0) blocks.size else it }
            headerApart(at)
            blocks.add(at, Block(emptyList(), "Host", host.name, render(host)))
        }
    }

    /*
     * A block added at k right below the header starts after a blank
     * line, a comment ending the header would be read back as its own.
     */
    private fun headerApart(k: Int) {
        if (k == 0 && header.lastOrNull()?.isBlank() == false) header = header + ""
    }

    /* The Host line of b naming name instead, what follows the old name, a comment, kept. */
    private fun renamed(b: Block, name: String): String {
        val line = b.line ?: return "${b.keyword} $name"
        val (keyword, value) = keyword(line) ?: return "${b.keyword} $name"
        val start = value.indexOfFirst { it != ' ' && it != '\t' }
        val rest = if (start < 0) "" else argument(value, start)?.let { value.substring(it.second) }.orEmpty()
        return line.takeWhile { it.isWhitespace() } + keyword + " " + name + rest
    }

    fun remove(name: String) {
        blocks.removeAll { it.name == name }
        if (footer.isNotEmpty() && blocks.isNotEmpty()) apart(blocks.lastIndex)
    }

    /*
     * End the block k with a blank line, which keeps it apart from the
     * next one, or the comments ending the file from it, see parse().
     */
    private fun apart(k: Int) {
        val b = blocks[k]
        if (b.lines.lastOrNull()?.isBlank() != true) blocks[k] = Block(b.leading, b.keyword, b.value, b.lines + "", b.line)
    }

    /**
     * Move the block of name, the one hosts lists, to position to of
     * hosts. ssh takes the first value it finds: the block only moves
     * past blocks of other single hosts, false is returned with nothing
     * changed when another block between could apply to it too, or when
     * it or one of them holds an IgnoreUnknown or Include line. ssh skips
     * an option it does not know only after an IgnoreUnknown line naming
     * it applied, the order of blocks then decides for the lines of the
     * others, read even where they do not apply. An included file may
     * hold one.
     */
    fun move(name: String, to: Int): Boolean {
        val firsts = blocks.indices.filter { blocks[it].isHost }.distinctBy { blocks[it].name }
        val i = firsts.find { blocks[it].name == name } ?: return true
        val j = firsts[to.coerceIn(firsts.indices)]
        val between = if (i < j) i + 1..j else j until i
        fun ordered(b: Block) = b.lines.any { line ->
            keyword(line)?.first.let { it.equals("IgnoreUnknown", true) || it.equals("Include", true) }
        }
        if (ordered(blocks[i]) ||
            between.any { k -> blocks[k].name.let { it == null || it.equals(name, ignoreCase = true) } || ordered(blocks[k]) })
            return false
        /* The last block may end without a blank line. */
        val last = blocks.last()
        blocks.add(j, blocks.removeAt(i))
        val k = blocks.lastIndexOf(last)
        if (k != blocks.lastIndex) apart(k)
        if (footer.isNotEmpty()) apart(blocks.lastIndex)
        return true
    }

    /**
     * Add the Host blocks of other whose names are not used here, as they
     * are written, like put() does. Its other lines and blocks are left
     * out, they could change the hosts already here. Returns the names
     * added.
     */
    fun addMissing(other: SshConfig): List<String> {
        val added = other.blocks.filter { b -> b.name?.let { !isUsed(it) } == true }
        if (added.isEmpty()) return emptyList()
        val at = blocks.indexOfFirst { !it.isHost }.let { if (it < 0) blocks.size else it }
        headerApart(at)
        blocks.addAll(at, added)
        /* Apart from each other and from the blocks around them, as the comments ending the file. */
        for (k in maxOf(at - 1, 0) until at + added.size)
            if (k != blocks.lastIndex || footer.isNotEmpty()) apart(k)
        return added.mapNotNull { it.name }.distinct()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (line in header) sb.append(line).append('\n')
        for (block in blocks) {
            for (line in block.leading) sb.append(line).append('\n')
            sb.append(block.line ?: "${block.keyword} ${block.value}").append('\n')
            for (line in block.lines) sb.append(line).append('\n')
        }
        for (line in footer) sb.append(line).append('\n')
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
            val (key, value) = keyword(line) ?: Pair("", "")
            when {
                key.equals("HostName", true) && hostName == null -> hostName = first(value)
                key.equals("User", true) && user == null -> user = first(value)
                key.equals("Port", true) && port == null -> port = first(value)
                key.equals("IdentityFile", true) && identity == null -> identity = first(value)
                key.equals("LocalForward", true) -> local.add(value)
                key.equals("RemoteForward", true) -> remote.add(value)
                key.equals("DynamicForward", true) -> dynamic.add(value)
                line.isNotBlank() -> other.add(trimOption(line))
            }
        }
        return Host(block.name.orEmpty(), hostName.orEmpty(), user.orEmpty(), port.orEmpty(),
            identity.orEmpty(), local, remote, dynamic, other)
    }

    private fun render(host: Host): List<String> {
        val lines = mutableListOf<String>()
        fun add(key: String, value: String) {
            if (value.isNotEmpty()) lines.add("$INDENT$key $value")
        }
        add("HostName", quote(host.hostName))
        add("User", quote(host.user))
        add("Port", quote(host.port))
        add("IdentityFile", quote(host.identityFile))
        host.localForwards.forEach { add("LocalForward", it) }
        host.remoteForwards.forEach { add("RemoteForward", it) }
        host.dynamicForwards.forEach { add("DynamicForward", it) }
        /*
         * Indented as the others when ssh reads the line the same way, see
         * trimOption(), which leaves the blanks of a line that needs them.
         */
        for (line in host.other.map { trimOption(it) }.filter { it.isNotEmpty() })
            lines.add(if (line[0].isWhitespace() || keyword(INDENT + line) != keyword(line)) line else INDENT + line)
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
            var first = ""
            var lines = mutableListOf<String>()
            val all = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')
            for (line in all) {
                val kv = keyword(line)
                if (kv != null && (kv.first.equals("Host", true) || kv.first.equals("Match", true))) {
                    /*
                     * Comments right above a block describe it, not the one
                     * before, unless indented beyond its line, as the options
                     * of that one are. After a block, blank lines between
                     * them are taken too, from the first comment on: in the
                     * header, a comment so apart is about the whole file.
                     */
                    val above = if (keyword == null) header else lines
                    val comments = takeComments(above, line, keyword != null)
                    if (keyword != null) blocks.add(Block(leading, keyword, value, lines, first))
                    leading = comments
                    keyword = kv.first
                    value = kv.second
                    first = line
                    lines = mutableListOf()
                } else if (keyword == null) {
                    header.add(line)
                } else {
                    lines.add(line)
                }
            }
            /*
             * Comments ending the file, a blank line apart from the last
             * block, belong to no host. Right below its options, up to the
             * first blank line, they are options commented out.
             */
            var footer = emptyList<String>()
            if (keyword != null) {
                val comments = takeComments(lines, first, true)
                /* Blank lines after the last comment are no gap. */
                val gap = comments.subList(0, comments.indexOfLast { it.isNotBlank() } + 1).indexOfFirst { it.isBlank() }
                val cut = when {
                    lines.lastOrNull()?.isBlank() == true -> 0
                    gap >= 0 -> gap + 1
                    else -> comments.size
                }
                lines.addAll(comments.subList(0, cut))
                footer = comments.subList(cut, comments.size)
                blocks.add(Block(leading, keyword, value, lines, first))
            }
            return SshConfig(header, blocks, footer)
        }

        /*
         * Remove and return the comments ending above, not indented beyond
         * line, from the first one: blank lines may come between them when
         * apart is set.
         */
        private fun takeComments(above: MutableList<String>, line: String, apart: Boolean): List<String> {
            fun comment(l: String) = l.trim().startsWith("#") && line.startsWith(l.takeWhile { it.isWhitespace() })
            val run = above.takeLastWhile { apart && it.isBlank() || comment(it) }
            val start = run.indexOfFirst { it.isNotBlank() }
            val comments = if (start < 0) emptyList() else run.subList(start, run.size).toList()
            repeat(comments.size) { above.removeAt(above.size - 1) }
            return comments
        }

        /**
         * Rewrite the IdentityFile lines, and the CertificateFile ones with
         * certificates, naming a file of paths, given as written in the
         * file, to the path it maps to.
         */
        fun replaceIdentities(text: String, paths: Map<String, String>, certificates: Boolean = true): String {
            if (text.isEmpty()) return text
            val lines = text.removeSuffix("\n").split('\n').map { line ->
                val kv = keyword(line)
                val to = kv?.let { paths[first(it.second)] }
                if (kv == null || to == null || !kv.first.equals("IdentityFile", true) &&
                    !(certificates && kv.first.equals("CertificateFile", true))) {
                    line
                } else {
                    /* What follows the path, a comment for one, stays. */
                    val rest = argument(kv.second, 0)?.let { kv.second.substring(it.second) }.orEmpty()
                    line.takeWhile { it.isWhitespace() } + kv.first + " " + quote(to) + rest
                }
            }
            return lines.joinToString("\n") + if (text.endsWith("\n")) "\n" else ""
        }

        /**
         * line without the blanks around it, those ssh skips, unless it
         * would read it otherwise. ssh skips one empty word before the
         * keyword, the indentation or a leading "": an indented line
         * starting with "" is one ssh skips, which it would read once
         * trimmed.
         */
        fun trimOption(line: String): String {
            fun blank(c: Char) = c in WHITESPACE || c == '\u000c'
            val trimmed = line.trim(::blank)
            return if (keyword(trimmed) == keyword(line)) trimmed else line.trimEnd(::blank)
        }

        /**
         * The keyword of a line and its arguments, read like ssh does in
         * readconf.c, or null for a line ssh skips. "Host"*, =Match and
         * "" Host are blocks to ssh, they must be to the app as well.
         */
        fun keyword(line: String): Pair<String, String>? {
            val s = line.trimEnd { it in WHITESPACE || it == '\u000c' }
            if (s.isEmpty()) return null
            var t = strdelim(s) ?: return null
            if (t.first.isEmpty()) t = strdelim(t.second ?: return null) ?: return null
            val key = t.first
            if (key.isEmpty() || key[0] == '#') return null
            return Pair(key, t.second?.trimStart { it in WHITESPACE } ?: "")
        }

        /*
         * The first token of s and what follows, as strdelim() in misc.c
         * takes it, the rest null at the end of the line. A quote ends
         * the token at the next one, null when there is none. One '=' may
         * stand between the token and the rest.
         */
        private fun strdelim(s: String): Pair<String, String?>? {
            val i = s.indexOfFirst { it in WHITESPACE || it == '"' || it == '=' }
            if (i < 0) return Pair(s, null)
            if (s[i] == '"') {
                val end = s.indexOf('"', i + 1)
                if (end < 0) return null
                return Pair(s.substring(0, i) + s.substring(i + 1, end), skipSpace(s, end + 1))
            }
            var rest = skipSpace(s, i + 1)
            if (s[i] != '=' && rest.startsWith("=")) rest = skipSpace(rest, 1)
            return Pair(s.substring(0, i), rest)
        }

        private fun skipSpace(s: String, from: Int): String =
            s.substring(from).trimStart { it in WHITESPACE }

        private const val WHITESPACE = " \t\r\n"

        /**
         * The arguments of a line after its keyword, as argv_split() in
         * misc.c takes them: quotes, backslash escapes and a comment from
         * a # starting an argument. Null at an unmatched quote, which ssh
         * refuses.
         */
        fun arguments(s: String): List<String>? {
            val args = mutableListOf<String>()
            var i = 0
            while (true) {
                while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
                if (i == s.length || s[i] == '#') return args
                val (arg, end) = argument(s, i) ?: return null
                args.add(arg)
                i = end
            }
        }

        /* The argument starting at s[from] and where it ends, null at an unmatched quote. */
        private fun argument(s: String, from: Int): Pair<String, Int>? {
            val arg = StringBuilder()
            var quote: Char? = null
            var i = from
            while (i < s.length) {
                val c = s[i]
                val next = s.getOrNull(i + 1)
                when {
                    c == '\\' && next != null && (next in "'\"\\" || quote == null && next == ' ') -> {
                        arg.append(next)
                        i++
                    }
                    quote == null && (c == ' ' || c == '\t') -> break
                    quote == null && (c == '"' || c == '\'') -> quote = c
                    quote != null && c == quote -> quote = null
                    else -> arg.append(c)
                }
                i++
            }
            return if (quote != null) null else Pair(arg.toString(), i)
        }

        /* The first argument, or the text as is when ssh would refuse it. */
        private fun first(s: String): String = arguments(s)?.let { it.firstOrNull().orEmpty() } ?: s

        /*
         * s written so that arguments() reads it back as one argument. A
         * leading = would be taken for the separator after the keyword.
         */
        private fun quote(s: String): String =
            if (!s.startsWith("=") && s.none { it.isWhitespace() || it in "\"'\\#" }) s
            else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
