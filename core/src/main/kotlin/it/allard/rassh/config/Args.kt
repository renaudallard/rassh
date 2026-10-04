package it.allard.rassh.config

object Args {
    /**
     * Split a command line in words as sh does. Whitespace separates
     * words, single and double quotes group them, a backslash escapes the
     * next character outside quotes and only " \ $ ` within double
     * quotes. A backslash at the end stays. Returns null on unbalanced
     * quotes.
     */
    fun split(s: String): List<String>? {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var quote = 0.toChar()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            when {
                quote == '\'' -> if (c == '\'') quote = 0.toChar() else word.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = 0.toChar()
                    c == '\\' && i < s.length && s[i] in "\"\\$`" -> word.append(s[i++])
                    else -> word.append(c)
                }
                c == '\\' -> {
                    word.append(if (i < s.length) s[i++] else c)
                    inWord = true
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    inWord = true
                }
                c.isWhitespace() -> if (inWord) {
                    words.add(word.toString())
                    word.setLength(0)
                    inWord = false
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
        }
        if (quote != 0.toChar()) return null
        if (inWord) words.add(word.toString())
        return words
    }
}
