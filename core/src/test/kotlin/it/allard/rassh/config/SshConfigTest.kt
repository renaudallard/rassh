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
        assertTrue(Host.isValidWord("user@example"))
        assertFalse(Host.isValidWord("a b"))
    }
}
