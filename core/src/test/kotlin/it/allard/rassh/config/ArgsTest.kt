package it.allard.rassh.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArgsTest {
    @Test
    fun splits() {
        assertEquals(listOf("user@host"), Args.split("user@host"))
        assertEquals(listOf("-p", "2222", "me@h"), Args.split("  -p 2222\tme@h "))
        assertEquals(listOf("-o", "ProxyJump a b", "h"), Args.split("-o \"ProxyJump a b\" h"))
        assertEquals(listOf("it's", "x y"), Args.split("it\\'s 'x y'"))
        assertEquals(listOf("", "a"), Args.split("'' a"))
        assertEquals(listOf("ab"), Args.split("a\"\"b"))
        assertEquals(emptyList(), Args.split("   "))
        assertEquals(listOf("h", "printf 'a\\nb'"), Args.split("h \"printf 'a\\nb'\""))
        assertEquals(listOf("a\"b\\c\$d"), Args.split("\"a\\\"b\\\\c\\\$d\""))
        assertEquals(listOf("trailing\\"), Args.split("trailing\\"))
        assertEquals(listOf("a\u00a0b"), Args.split("a\u00a0b"))
    }

    @Test
    fun dropsComments() {
        assertEquals(listOf("web"), Args.split("web # prod"))
        assertEquals(listOf("a#b", "#c"), Args.split("a#b '#c'"))
        assertEquals(listOf("a", "b"), Args.split("a #x 'y\nb"))
        assertEquals(listOf("a", "#"), Args.split("a \\#"))
    }

    @Test
    fun rejectsUnbalanced() {
        assertNull(Args.split("'open"))
        assertNull(Args.split("\"open"))
    }
}
