package it.allard.rassh.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshConfigTest {
    private val sample = """
        |# global
        |ServerAliveInterval 30
        |
        |Host web
        |    HostName=example.org
        |    User root
        |    Port 2222
        |    IdentityFile "~/.ssh/my key"
        |    LocalForward 8080 localhost:80
        |    LocalForward 8443 localhost:443
        |    # a comment
        |    Compression yes
        |
        |Host *.lan other
        |    User pi
        |
        |Match host db
        |    User postgres
        |""".trimMargin()

    @Test
    fun parsesHosts() {
        val config = SshConfig.parse(sample)
        assertEquals(1, config.hosts.size)
        val web = config.hosts[0]
        assertEquals("web", web.name)
        assertEquals("example.org", web.hostName)
        assertEquals("root", web.user)
        assertEquals("2222", web.port)
        assertEquals("~/.ssh/my key", web.identityFile)
        assertEquals(listOf("8080 localhost:80", "8443 localhost:443"), web.localForwards)
        assertEquals(listOf("# a comment", "Compression yes"), web.other)
    }

    @Test
    fun roundTripsUnchanged() {
        val text = sample + "\n"
        assertEquals(text, SshConfig.parse(text).toString())
    }

    @Test
    fun rewritesHost() {
        val config = SshConfig.parse(sample)
        val web = config.find("web")!!
        config.put("web", web.copy(name = "www", port = "", dynamicForwards = listOf("1080")))
        val again = SshConfig.parse(config.toString())
        assertNull(again.find("web"))
        val www = again.find("www")!!
        assertEquals("", www.port)
        assertEquals(listOf("1080"), www.dynamicForwards)
        assertEquals(web.localForwards, www.localForwards)
        assertEquals(web.other, www.other)
        assertTrue(config.toString().contains("    IdentityFile \"~/.ssh/my key\"\n"))
        assertTrue(config.toString().startsWith("# global\nServerAliveInterval 30\n\nHost www\n"))
    }

    @Test
    fun addsBeforeWildcards() {
        val config = SshConfig.parse(sample)
        config.put(null, Host("new", hostName = "10.0.0.1"))
        val text = config.toString()
        assertTrue(text.indexOf("Host new") < text.indexOf("Host *.lan"))
        assertTrue(text.indexOf("Host web") < text.indexOf("Host new"))
        assertEquals(listOf("web", "new"), SshConfig.parse(text).hosts.map { it.name })
    }

    @Test
    fun removesHost() {
        val config = SshConfig.parse(sample)
        config.remove("web")
        assertEquals(0, config.hosts.size)
        assertTrue(config.toString().contains("Host *.lan other"))
    }

    @Test
    fun emptyFile() {
        val config = SshConfig.parse("")
        assertEquals("", config.toString())
        config.put(null, Host("a", user = "me"))
        assertEquals("Host a\n    User me\n\n", config.toString())
    }

    @Test
    fun quotedPort() {
        assertEquals("2222", SshConfig.parse("Host q\n    Port \"2222\"\n").hosts[0].port)
    }

    @Test
    fun duplicateNamesListedOnce() {
        val config = SshConfig.parse("Host a\n    User x\n\nHost a\n    LocalForward 8080 x:80\n")
        assertEquals(listOf("a" to "x"), config.hosts.map { it.name to it.user })
        config.remove("a")
        assertEquals(0, config.hosts.size)
    }

    @Test
    fun commentsStayAboveTheirBlock() {
        val text = "Host a\n    HostName a.example\n\n# Work servers\nHost b\n    HostName b.example\n" +
            "\n# Defaults for every host\nHost *\n    User me\n"
        val removed = SshConfig.parse(text)
        removed.remove("a")
        assertEquals("# Work servers\nHost b\n    HostName b.example\n\n# Defaults for every host\nHost *\n    User me\n",
            removed.toString())
        val added = SshConfig.parse(text)
        added.put(null, Host("c", user = "x"))
        assertTrue(added.toString().contains("Host c\n    User x\n\n# Defaults for every host\nHost *\n"))
        val edited = SshConfig.parse(text)
        edited.put("b", edited.find("b")!!.copy(user = "y"))
        assertTrue(edited.toString().contains("# Work servers\nHost b\n    HostName b.example\n    User y\n"))
        assertEquals(text, SshConfig.parse(text).toString())
    }

    @Test
    fun replaceIdentities() {
        val text = """
            |IdentityFile ~/.ssh/id_rsa
            |Host a
            |    IdentityFile ~/.ssh/id_ed25519
            |    identityfile="/home/u/.ssh/work key"
            |    IdentityFile ~/.ssh/other
            |# IdentityFile ~/.ssh/id_ed25519
            |""".trimMargin()
        val keys = listOf("~/.ssh/id_ed25519", "/home/u/.ssh/work key", "~/.ssh/id_rsa").associateWith { "$it.pub" }
        assertEquals("""
            |IdentityFile ~/.ssh/id_rsa.pub
            |Host a
            |    IdentityFile ~/.ssh/id_ed25519.pub
            |    identityfile "/home/u/.ssh/work key.pub"
            |    IdentityFile ~/.ssh/other
            |# IdentityFile ~/.ssh/id_ed25519
            |""".trimMargin(), SshConfig.replaceIdentities(text, keys))
        assertEquals("", SshConfig.replaceIdentities("", keys))
        assertEquals("Host b", SshConfig.replaceIdentities("Host b", keys))
        assertEquals("IdentityFile ~/.ssh/new.pub\n",
            SshConfig.replaceIdentities("IdentityFile ~/.ssh/old.pub\n", mapOf("~/.ssh/old.pub" to "~/.ssh/new.pub")))
    }

    @Test
    fun validation() {
        assertTrue(Host.isValidName("my-host.example"))
        assertFalse(Host.isValidName(""))
        assertFalse(Host.isValidName("a b"))
        assertFalse(Host.isValidName("*.lan"))
        assertFalse(Host.isValidName("!a"))
        assertTrue(Host.isValidPort(""))
        assertTrue(Host.isValidPort("22"))
        assertFalse(Host.isValidPort("0"))
        assertFalse(Host.isValidPort("65536"))
        assertFalse(Host.isValidPort("99999999999"))
        assertFalse(Host.isValidPort("2a"))
        assertTrue(Host.isValidOption("Compression yes"))
        assertTrue(Host.isValidOption("# Host comment"))
        assertFalse(Host.isValidOption("host other"))
        assertFalse(Host.isValidOption("Match=all"))
        assertFalse(Host.isValidOption("\"Host\" *"))
        assertFalse(Host.isValidOption("Ho\"st\" x"))
        assertFalse(Host.isValidOption("\"Host\"*"))
        assertFalse(Host.isValidOption("=Match all"))
        assertFalse(Host.isValidOption("\"\" Host *"))
        assertFalse(Host.isValidOption("  Host = x"))
        assertTrue(Host.isValidOption("\"HostName\" example.org"))
        assertTrue(Host.isValidOption("HostKeyAlgorithms=+ssh-rsa"))
        assertTrue(Host.isValidOption("\"Host unterminated"))
        assertTrue(Host.isValidWord("user@example"))
        assertFalse(Host.isValidWord("a b"))
    }

    @Test
    fun addsMissingHosts() {
        val config = SshConfig.parse(sample)
        val other = SshConfig.parse("""
            |ServerAliveInterval 5
            |
            |# the pi
            |Host pi
            |    HostName 10.0.0.2
            |
            |Host web
            |    HostName elsewhere.org
            |
            |Host *
            |    User nobody
            |""".trimMargin())
        assertEquals(listOf("pi"), config.addMissing(other))
        assertEquals(listOf("web", "pi"), config.hosts.map { it.name })
        assertEquals("example.org", config.find("web")?.hostName)
        val text = config.toString()
        assertTrue(text.contains("# the pi\nHost pi\n    HostName 10.0.0.2\n"))
        assertTrue(text.indexOf("Host pi") < text.indexOf("Host *.lan"))
        assertFalse(text.contains("ServerAliveInterval 5"))
        assertFalse(text.contains("nobody"))
    }

    @Test
    fun readsKeywordsLikeSsh() {
        assertEquals(Pair("Host", "*"), SshConfig.keyword("\"Host\"*"))
        assertEquals(Pair("Host", "x"), SshConfig.keyword("Ho\"st\" x"))
        assertEquals(Pair("Match", "all"), SshConfig.keyword("=Match all"))
        assertEquals(Pair("Host", "*"), SshConfig.keyword("\"\" Host *"))
        assertEquals(Pair("User", "root"), SshConfig.keyword("  User = root  "))
        assertEquals(Pair("Port", "22"), SshConfig.keyword("Port=22"))
        assertEquals(Pair("IdentityFile", "\"~/.ssh/my key\""), SshConfig.keyword("IdentityFile \"~/.ssh/my key\""))
        assertNull(SshConfig.keyword("# Host x"))
        assertNull(SshConfig.keyword("   "))
        assertNull(SshConfig.keyword("\"Host x"))
    }

    @Test
    fun quotedHostStartsABlock() {
        val config = SshConfig.parse("Host a\n    User x\n\"Host\" b\n    User y\n")
        assertEquals(listOf("a", "b"), config.hosts.map { it.name })
        assertEquals("y", config.find("b")?.user)
        assertEquals("x", config.find("a")?.user)
    }

    @Test
    fun readsArgumentsLikeSsh() {
        assertEquals(listOf("~/.ssh/id"), SshConfig.arguments("~/.ssh/id # work key"))
        assertEquals(listOf("~/.ssh/my key"), SshConfig.arguments("'~/.ssh/my key'"))
        assertEquals(listOf("~/.ssh/my key"), SshConfig.arguments("~/.ssh/my\\ key"))
        assertEquals(listOf("a#b", "c"), SshConfig.arguments("a#b c"))
        assertEquals(listOf("a\"b"), SshConfig.arguments("\"a\\\"b\""))
        assertEquals(null, SshConfig.arguments("\"open"))
    }

    @Test
    fun keepsValuesWhenSaving() {
        val config = SshConfig.parse("""
            |Host a
            |    HostName h # the box
            |    IdentityFile ~/.ssh/id # work key
            |Host b
            |    IdentityFile '~/.ssh/my key'
            |""".trimMargin())
        assertEquals("h", config.find("a")?.hostName)
        assertEquals("~/.ssh/id", config.find("a")?.identityFile)
        assertEquals("~/.ssh/my key", config.find("b")?.identityFile)
        config.put("a", config.find("a")!!)
        config.put("b", config.find("b")!!)
        val text = config.toString()
        assertTrue(text.contains("    HostName h\n    IdentityFile ~/.ssh/id\n"))
        assertTrue(text.contains("    IdentityFile \"~/.ssh/my key\"\n"))
        assertEquals("~/.ssh/my key", SshConfig.parse(text).find("b")?.identityFile)
    }

    @Test
    fun quotesWhatNeedsIt() {
        val config = SshConfig.parse("")
        config.put(null, Host("q", identityFile = "~/.ssh/a \"b\" \\c"))
        assertEquals("~/.ssh/a \"b\" \\c", SshConfig.parse(config.toString()).find("q")?.identityFile)
    }

    @Test
    fun replacesCommentedIdentities() {
        val text = "Host a\n    IdentityFile ~/.ssh/k # old\n"
        assertEquals("Host a\n    IdentityFile ~/.ssh/k.pub\n",
            SshConfig.replaceIdentities(text, mapOf("~/.ssh/k" to "~/.ssh/k.pub")))
    }

    @Test
    fun quotesLeadingEquals() {
        val config = SshConfig.parse("")
        config.put(null, Host("e", user = "=x"))
        assertEquals("=x", SshConfig.parse(config.toString()).find("e")?.user)
    }

    @Test
    fun namesSshAccepts() {
        assertTrue(Host.isValidName("web-1.example.org"))
        for (bad in listOf("-v", "a;b", "a\$b", "a`b", "a|b", "a(b", "a{b", "a\\b", "a b", "a\tb"))
            assertFalse(Host.isValidName(bad), bad)
    }
}
