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
    fun removesLastHostApartFromTheEnd() {
        val config = SshConfig.parse("Host a\n  User x\nHost b\n  User y\n\n# end\n")
        config.remove("b")
        assertEquals("Host a\n  User x\n\n# end\n", config.toString())
        assertEquals(emptyList(), SshConfig.parse(config.toString()).find("a")!!.other)
        config.remove("a")
        assertEquals("# end\n", config.toString())
    }

    @Test
    fun emptyFile() {
        val config = SshConfig.parse("")
        assertEquals("", config.toString())
        config.put(null, Host("a", user = "me"))
        assertEquals("Host a\n    User me\n\n", config.toString())
    }

    @Test
    fun addsApartFromTheHeader() {
        val config = SshConfig.parse("# notes\n")
        config.put(null, Host("a", user = "me"))
        val again = SshConfig.parse(config.toString())
        again.remove("a")
        assertEquals("# notes\n\n", again.toString())
        val added = SshConfig.parse("User me\n")
        added.addMissing(SshConfig.parse("Host b\n"))
        assertEquals("User me\n\nHost b\n", added.toString())
        val blank = SshConfig.parse("# notes\n\n")
        blank.put(null, Host("a"))
        assertEquals("# notes\n\nHost a\n\n", blank.toString())
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
        val renamed = SshConfig.parse("Host a\n    User x\n\nHost a\n    Port 2222\n")
        assertFalse(renamed.isUsed("A", "a"))
        renamed.put("a", renamed.find("a")!!.copy(name = "b"))
        assertEquals("Host b\n    User x\n\nHost b\n    Port 2222\n", renamed.toString())
    }

    @Test
    fun renameKeepsHostLineComments() {
        val config = SshConfig.parse("Host a # production\n    User x\n\nHost=a\t#more\n    Port 2222\n")
        config.put("a", config.find("a")!!.copy(name = "b"))
        assertEquals("Host b # production\n    User x\n\nHost b\t#more\n    Port 2222\n", config.toString())
        assertEquals(listOf("b"), config.hosts.map { it.name })
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
    fun movesHost() {
        val text = "# global\nUser me\n\n# Work\nHost a\n    HostName a.example\n\nHost b\n    Port 2222\n\n" +
            "Host c\n    HostName c.example\n\nHost *\n    User you\n"
        val up = SshConfig.parse(text)
        assertTrue(up.move("c", 0))
        assertEquals("# global\nUser me\n\nHost c\n    HostName c.example\n\n# Work\nHost a\n    HostName a.example\n\n" +
            "Host b\n    Port 2222\n\nHost *\n    User you\n", up.toString())
        val down = SshConfig.parse(text)
        assertTrue(down.move("a", 1))
        assertEquals(listOf("b", "a", "c"), SshConfig.parse(down.toString()).hosts.map { it.name })
        assertTrue(down.move("a", 99))
        assertEquals(listOf("b", "c", "a"), SshConfig.parse(down.toString()).hosts.map { it.name })
        assertTrue(down.move("x", 0))
        assertEquals(listOf("b", "c", "a"), down.hosts.map { it.name })
    }

    @Test
    fun movesLastHostApart() {
        val config = SshConfig.parse("Host a\n    User x\n\nHost b\n    User y\n")
        assertTrue(config.move("b", 0))
        assertEquals("Host b\n    User y\n\nHost a\n    User x\n\n", config.toString())
        assertTrue(config.move("b", 1))
        assertEquals("Host a\n    User x\n\nHost b\n    User y\n\n", config.toString())
    }

    @Test
    fun movesLastHostApartFromTheEnd() {
        val config = SshConfig.parse("Host a\n  User x\nHost b\n  User y\n\n# end\n")
        assertTrue(config.move("a", 1))
        assertEquals("Host b\n  User y\n\nHost a\n  User x\n\n# end\n", config.toString())
        assertEquals(emptyList(), SshConfig.parse(config.toString()).find("a")!!.other)
    }

    @Test
    fun movesOnlyPastOtherHosts() {
        for (text in listOf(
            "Host a\n    User x\n\nHost *\n    User y\n\nHost b\n",
            "Host a\n    User x\n\nMatch host b\n    User y\n\nHost b\n",
            "Host a\n    User x\n\nHost b a\n    User y\n\nHost b\n",
            "Host a\n    User x\n\nHost b\n\nHost a\n    Port 2222\n\nHost c\n",
            "Host a\n    User x\n\nHost A\n    Port 2222\n\nHost b\n",
            "Host a\n    IgnoreUnknown UseKeychain\n\nHost b\n    IgnoreUnknown UseKeychain\n    UseKeychain yes\n",
            "Host a\n    HostName a.example\n\nHost b\n    ignoreunknown=UseKeychain\n",
            "Host a\n    HostName a.example\n\nHost b\n    Include ~/.ssh/b.conf\n",
            "Host a\n    \"Include\" ~/.ssh/a.conf\n\nHost b\n",
        )) {
            val config = SshConfig.parse(text)
            assertFalse(config.move("a", config.hosts.lastIndex))
            assertEquals(text, config.toString())
        }
        val config = SshConfig.parse("Host a\n\nHost b\n\nHost a\n    Port 2222\n")
        assertTrue(config.move("b", 0))
        assertEquals("Host b\n\nHost a\n\nHost a\n    Port 2222\n", config.toString())
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
        assertEquals("    IdentityFile ~/.ssh/new # work key\n",
            SshConfig.replaceIdentities("    IdentityFile \"~/.ssh/old\" # work key\n", mapOf("~/.ssh/old" to "~/.ssh/new")))
        assertEquals("CertificateFile ~/.ssh/new-cert.pub\n",
            SshConfig.replaceIdentities("CertificateFile ~/.ssh/old-cert.pub\n", mapOf("~/.ssh/old-cert.pub" to "~/.ssh/new-cert.pub")))
    }

    @Test
    fun validation() {
        assertTrue(Host.isValidName("my-host.example"))
        assertFalse(Host.isValidName(""))
        assertFalse(Host.isValidName("a b"))
        assertFalse(Host.isValidName("[web]"))
        assertFalse(Host.isValidName("*.lan"))
        assertFalse(Host.isValidName("!a"))
        assertTrue(Host.isValidPort(""))
        assertTrue(Host.isValidPort("22"))
        assertFalse(Host.isValidPort("0"))
        assertTrue(Host.isValidPort("000022"))
        assertFalse(Host.isValidPort("00000"))
        assertFalse(Host.isValidPort("0065536"))
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
        assertTrue(Host.isValidWord("%h.example.org"))
        assertFalse(Host.isValidWord("a b"))
        assertFalse(Host.isValidWord("x\$(id)"))
        assertFalse(Host.isValidWord("-oProxyCommand=id"))
        assertFalse(Host.isValidWord("a\u0000b"))
        assertTrue(Host.isValidUser("John Smith"))
        assertTrue(Host.isValidUser("me#1"))
        assertFalse(Host.isValidUser("o'brien"))
        assertFalse(Host.isValidUser("x\$(id)"))
        assertFalse(Host.isValidUser("me -x"))
        assertFalse(Host.isValidUser("a\nb"))
        val config = SshConfig.parse("")
        config.put(null, Host("w", user = "John \"J\" Smith"))
        assertEquals("John \"J\" Smith", SshConfig.parse(config.toString()).find("w")!!.user)
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
    fun addsApartFromTheEnd() {
        val config = SshConfig.parse("Host a\n  User x\n\n# end\n")
        assertEquals(listOf("b"), config.addMissing(SshConfig.parse("Host b\n  User y\n")))
        assertEquals("Host a\n  User x\n\nHost b\n  User y\n\n# end\n", config.toString())
        assertEquals(emptyList(), SshConfig.parse(config.toString()).find("b")!!.other)
        val bare = SshConfig.parse("Host a\n  User x\n")
        bare.addMissing(SshConfig.parse("Host b\n  User y\nHost c\n  User z\n"))
        assertEquals("Host a\n  User x\n\nHost b\n  User y\n\nHost c\n  User z\n", bare.toString())
        val none = SshConfig.parse("Host a\n  User x\n")
        assertEquals(emptyList(), none.addMissing(SshConfig.parse("Host a\n  User y\n")))
        assertEquals("Host a\n  User x\n", none.toString())
    }

    @Test
    fun namesOfSharedBlocksAreUsed() {
        val config = SshConfig.parse("Host web db\n    User admin\nHost mail # prod\nHost !x *.lan\n")
        assertTrue(config.isUsed("db"))
        assertTrue(config.isUsed("Mail"))
        assertFalse(config.isUsed("x"))
        assertFalse(config.isUsed("prod"))
        val cases = SshConfig.parse("Host Foo\n    User alice\nHost foo\n    User bob\nHost Bar\n")
        assertTrue(cases.isUsed("foo", "Foo"))
        assertFalse(cases.isUsed("bar", "Bar"))
        val other = SshConfig.parse("Host web\n    User root\nHost mail\nHost pi\n")
        assertEquals(listOf("pi"), config.addMissing(other))
    }

    @Test
    fun namesListsEveryPlainName() {
        val config = SshConfig.parse("Host web db\nHost mail # prod\nHost !x *.lan\nMatch host y\nHost web\n")
        assertEquals(listOf("web", "db", "mail"), config.names)
    }

    @Test
    fun indentedCommentStaysWithItsBlock() {
        val config = SshConfig.parse("Host *\n    User u\n    # ForwardAgent yes\n# the work host\nHost work\n    HostName w\n")
        config.remove("work")
        assertEquals("Host *\n    User u\n    # ForwardAgent yes\n", config.toString())
        val tabs = SshConfig.parse("  Host a\n\tUser u\n\t# a option\n  Host b\n\tUser v\n")
        tabs.remove("b")
        assertEquals("  Host a\n\tUser u\n\t# a option\n", tabs.toString())
    }

    @Test
    fun commentApartGoesWithTheBlockBelow() {
        val text = "# my config\n\nHost a\n    HostName x\n\n# Defaults for every host\n\nHost *\n    User me\n"
        val config = SshConfig.parse(text)
        config.remove("a")
        assertEquals("# my config\n\n# Defaults for every host\n\nHost *\n    User me\n", config.toString())
        val edited = SshConfig.parse(text)
        edited.put("a", Host("a", hostName = "y"))
        assertTrue(edited.toString().contains("    HostName y\n\n# Defaults for every host\n\nHost *"))
    }

    @Test
    fun commentsEndingTheFileStay() {
        val text = "Host a\n    User x\n\nHost b\n    User y\n    # b option\n\n# Host old\n#     HostName z\n"
        val removed = SshConfig.parse(text)
        removed.remove("b")
        assertEquals("Host a\n    User x\n\n# Host old\n#     HostName z\n", removed.toString())
        val edited = SshConfig.parse(text)
        assertEquals(listOf("# b option"), edited.find("b")!!.other)
        edited.put("b", edited.find("b")!!.copy(user = "z"))
        assertTrue(edited.toString().endsWith("    User z\n    # b option\n\n# Host old\n#     HostName z\n"))
        val added = SshConfig.parse(text)
        added.put(null, Host("c", user = "w"))
        assertTrue(added.toString().endsWith("Host c\n    User w\n\n# Host old\n#     HostName z\n"))
        assertEquals(text, SshConfig.parse(text).toString())
        val below = SshConfig.parse("Host b\n    HostName x\n#    Port 2222\n")
        assertEquals(listOf("#    Port 2222"), below.find("b")!!.other)
        below.put(null, Host("c", user = "w"))
        assertTrue(below.toString().startsWith("Host b\n    HostName x\n#    Port 2222\nHost c\n"))
        val both = SshConfig.parse("Host b\n    User y\n# Port 22\n\n# Host old\n#     HostName z\n")
        assertEquals(listOf("# Port 22"), both.find("b")!!.other)
        both.remove("b")
        assertEquals("# Host old\n#     HostName z\n", both.toString())
        val notes = SshConfig.parse("Host b\n    User y\n# Port 22\n\n# note 1\n\n# note 2\n")
        assertEquals(listOf("# Port 22"), notes.find("b")!!.other)
        notes.remove("b")
        assertEquals("# note 1\n\n# note 2\n", notes.toString())
    }

    @Test
    fun emptyWordBeforeTheKeywordStaysUnindented() {
        val text = "Host web\n    HostName 192.0.2.1\n\"\" ProxyJump bastion\n"
        val config = SshConfig.parse(text)
        config.put("web", config.find("web")!!)
        assertEquals(text + "\n", config.toString())
        /* Indented, ssh skips it, and still does once saved, again and again. */
        val skipped = SshConfig.parse("Host web\n    \"\" ProxyJump bastion\n")
        repeat(2) { skipped.put("web", skipped.find("web")!!) }
        assertEquals("Host web\n    \"\" ProxyJump bastion\n\n", skipped.toString())
        assertEquals("\t\"\" x", SshConfig.trimOption("\t\"\" x  "))
        assertEquals("User x", SshConfig.trimOption("  User x "))
        /* ssh keeps a trailing no-break space in a value. */
        assertEquals("SetEnv A=b\u00a0", SshConfig.trimOption("    SetEnv A=b\u00a0 "))
    }

    @Test
    fun hostLineWithAComment() {
        val config = SshConfig.parse("Host web # office\n    User a\n")
        assertEquals(listOf("web"), config.hosts.map { it.name })
        config.put("web", Host("web", user = "b"))
        assertTrue(config.toString().startsWith("Host web # office\n    User b\n"))
        config.put("web", Host("www", user = "b"))
        assertTrue(config.toString().startsWith("Host www # office\n"))
    }

    @Test
    fun keepsBlockLinesAsWritten() {
        val text = "Host==foo\n    User x\n  host  =  a  \nMatch=all\n"
        val config = SshConfig.parse(text)
        config.put(null, Host("b"))
        assertTrue(config.toString().startsWith("Host b\n"))
        assertTrue(config.toString().endsWith(text))
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
        assertEquals("Host a\n    IdentityFile ~/.ssh/k.pub # old\n",
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
        assertTrue(Host.isValidName("fe80::1"))
        for (bad in listOf("a@b", "ssh://x", "-v", "a;b", "a\$b", "a`b", "a|b", "a(b", "a{b", "a\\b", "a b", "a\tb"))
            assertFalse(Host.isValidName(bad), bad)
    }
}
