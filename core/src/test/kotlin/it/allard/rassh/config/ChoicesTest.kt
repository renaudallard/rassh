package it.allard.rassh.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChoicesTest {
    private fun choice(keyword: String) = Choices.ALL.first { it.keyword == keyword }

    @Test
    fun takesKnownValues() {
        val (taken, rest) = Choices.take(listOf("compression=YES", "ServerAliveInterval 30", "ProxyJump j"))
        assertEquals(mapOf(choice("Compression") to "yes", choice("ServerAliveInterval") to "30"), taken)
        assertEquals(listOf("ProxyJump j"), rest)
    }

    @Test
    fun keepsOtherValues() {
        val lines = listOf("ServerAliveInterval 45", "Compression yes # fast", "LogLevel QUIET", "ConnectTimeout \"5\"")
        val (taken, rest) = Choices.take(lines)
        assertTrue(taken.isEmpty())
        assertEquals(lines, rest)
    }

    @Test
    fun keepsRepeatedKeywords() {
        val lines = listOf("Compression yes", "compression no")
        val (taken, rest) = Choices.take(lines)
        assertTrue(taken.isEmpty())
        assertEquals(lines, rest)
    }

    @Test
    fun findsKeyword() {
        assertEquals(choice("TCPKeepAlive"), Choices.of("  tcpkeepalive=no"))
        /* The obsolete name ssh still reads as TCPKeepAlive. */
        assertEquals(choice("TCPKeepAlive"), Choices.of("KeepAlive no"))
        assertTrue(Choices.take(listOf("KeepAlive no", "TCPKeepAlive yes")).first.isEmpty())
        assertNull(Choices.of("# Compression yes"))
        assertNull(Choices.of("Ciphers aes256-ctr"))
    }

    @Test
    fun readsDump() {
        val dump = "user r\r\ncompression no\r\nstricthostkeychecking true\r\nupdatehostkeys false\r\n" +
            "loglevel INFO\r\nconnecttimeout none\r\n"
        assertEquals(mapOf(
            choice("Compression") to "no",
            choice("StrictHostKeyChecking") to "yes",
            choice("UpdateHostKeys") to "no",
            choice("LogLevel") to "INFO",
            choice("ConnectTimeout") to "none",
        ), Choices.effective(dump))
        /* A carriage return within a value is no line of its own. */
        assertEquals(mapOf(choice("Compression") to "no"),
            Choices.effective("compression no\nsetenv A=1\rcompression yes\n"))
    }

    @Test
    fun writesLine() {
        assertEquals("UpdateHostKeys ask", Choices.line(choice("UpdateHostKeys"), "ask"))
    }
}
