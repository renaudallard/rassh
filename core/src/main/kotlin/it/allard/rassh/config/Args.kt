package it.allard.rassh.config

object Args {
    /**
     * Split a command line in words. Whitespace separates words, single
     * and double quotes group them and a backslash escapes the next
     * character outside single quotes. Returns null on unbalanced quotes.
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
                c == '\\' -> {
                    if (i == s.length) return null
                    word.append(s[i++])
                    inWord = true
                }
                quote == '"' -> if (c == '"') quote = 0.toChar() else word.append(c)
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
