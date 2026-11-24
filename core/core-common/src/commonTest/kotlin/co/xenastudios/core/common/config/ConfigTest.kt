package co.xenastudios.core.common.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.text.MarkupTag
import co.xenastudios.core.common.text.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConfigTest {

    private fun load(text: String?, context: ConfigContext = ConfigContext.DEFAULT): ConfigLoad =
        assertIs<Outcome.Ok<ConfigLoad>>(Configs.load(HomesConfig, text, TestYaml, context)).value

    private fun kinds(load: ConfigLoad) = load.problems.map { it.kind }

    @Test
    fun theDefaultFileIsWrittenInSchemaOrderWithComments() {
        assertEquals(DEFAULT_FILE, Configs.defaultText(HomesConfig))
    }

    @Test
    fun noFileWritesTheDefaults() {
        val load = load(null)
        assertEquals(DEFAULT_FILE, load.write)
        assertEquals(listOf(ConfigNotice.Kind.WROTE_DEFAULTS), load.notices.map { it.kind })
        assertEquals(HomesConfig.defaults, load.config)
        assertEquals(false, load.backup)
    }

    @Test
    fun theDefaultFileReadsBackAsTheDefaultsAndIsLeftAlone() {
        val load = load(DEFAULT_FILE)
        assertEquals(emptyList(), load.problems)
        assertEquals(emptyList(), load.notices)
        assertNull(load.write)
        val config = load.config
        assertEquals("auto", config[HomesConfig.language])
        assertEquals(Storage.SQLITE, config[HomesConfig.storage])
        assertEquals(90.seconds, config[HomesConfig.homes.delay])
        assertEquals(listOf(1, 2, 3), config[HomesConfig.homes.slots])
        assertEquals("<green>Welcome home, <player>!", config[HomesConfig.messages.welcome].source)
    }

    @Test
    fun aFileReadsItsOwnersValues() {
        val config = load(
            """
            config-version: 2
            language: de
            storage: flat-file
            homes:
              limit: 10
              teleport-delay: 1h30m
              enabled: no
              cost: 0
              blocked-worlds: [a, "b c"]
              slots:
                - 4
                - 5
            messages:
              welcome: '<red>Hi'
              prefix: "[H] "
            """.trimIndent(),
        ).config
        assertEquals("de", config[HomesConfig.language])
        assertEquals(Storage.FLAT_FILE, config[HomesConfig.storage])
        assertEquals(10, config[HomesConfig.homes.limit])
        assertEquals(90.minutes, config[HomesConfig.homes.delay])
        assertEquals(false, config[HomesConfig.homes.enabled])
        assertEquals(0.0, config[HomesConfig.homes.cost])
        assertEquals(listOf("a", "b c"), config[HomesConfig.homes.blockedWorlds])
        assertEquals(listOf(4, 5), config[HomesConfig.homes.slots])
        assertEquals("Hi", config[HomesConfig.messages.welcome].render().plain())
        assertEquals("[H] ", config[HomesConfig.messages.prefix])
    }

    @Test
    fun aBadValueIsReportedWithPathAndLineAndTheDefaultUsedButKeptInTheFile() {
        val text = DEFAULT_FILE.replace("limit: 3", "limit: three").replace("teleport-delay: \"1m30s\"", "teleport-delay: 5x")
        val load = load(text)
        val limit = load.problems.single { it.path == "homes.limit" }
        assertEquals(ConfigProblem.Kind.BAD_VALUE, limit.kind)
        assertEquals(lineOf(text, "limit: three"), limit.line)
        assertEquals("three", limit.found)
        assertEquals("homes.limit (\"three\") should be a whole number; using the default.", limit.message)
        assertEquals(
            "homes.teleport-delay (\"5x\") has a unit that isn't one (\"x\"); use ms, s, m, h, d, w, mo or y; using the default.",
            load.problems.single { it.path == "homes.teleport-delay" }.message,
        )
        assertEquals(3, load.config[HomesConfig.homes.limit])
        assertEquals(90.seconds, load.config[HomesConfig.homes.delay])
        // Nothing else changed, so nothing is written, and the owner's text stays theirs.
        assertNull(load.write)
    }

    @Test
    fun aValueOutsideItsRangeIsReported() {
        val load = load(DEFAULT_FILE.replace("limit: 3", "limit: 500").replace("cost: 2.5", "cost: -1"))
        assertEquals("homes.limit (\"500\") must be from 0 to 100; using the default.", load.problems[0].message)
        assertEquals("homes.cost (\"-1\") must be at least 0; using the default.", load.problems[1].message)
        assertEquals(3, load.config[HomesConfig.homes.limit])
    }

    @Test
    fun aListKeepsTheItemsItCanReadAndOneValueIsAListOfOne() {
        val text = DEFAULT_FILE.replace("slots: [1, 2, 3]", "slots: [1, two, 3]").replace("blocked-worlds: [\"world_nether\"]", "blocked-worlds: lobby")
        val load = load(text)
        assertEquals(listOf(1, 3), load.config[HomesConfig.homes.slots])
        assertEquals(listOf("lobby"), load.config[HomesConfig.homes.blockedWorlds])
        val problem = load.problems.single()
        assertEquals("homes.slots[1]", problem.path)
        assertEquals("homes.slots[1] (\"two\") should be a whole number; so it's skipped.", problem.message)
    }

    @Test
    fun anEmptyValueIsBlankWhereThatMeansSomethingAndTheDefaultElsewhere() {
        val load = load(DEFAULT_FILE.replace("prefix: \"\"", "prefix:").replace("limit: 3", "limit:").replace("slots: [1, 2, 3]", "slots:"))
        assertEquals("", load.config[HomesConfig.messages.prefix])
        assertEquals(emptyList(), load.config[HomesConfig.homes.slots])
        assertEquals(3, load.config[HomesConfig.homes.limit])
        assertEquals(listOf(ConfigProblem.Kind.EMPTY), kinds(load))
    }

    @Test
    fun unknownKeysAreReportedAndKept() {
        val text = DEFAULT_FILE.replace("  enabled: true", "  enabled: true\n  limt: 5") + "\nextra:\n  nested: [1, \"a b\"]\n"
        val load = load(text)
        assertEquals(listOf("homes.limt", "extra"), load.problems.map { it.path })
        assertEquals(listOf(ConfigProblem.Kind.UNKNOWN_KEY, ConfigProblem.Kind.UNKNOWN_KEY), kinds(load))
        assertEquals(lineOf(text, "limt: 5"), load.problems[0].line)
        assertNull(load.write)

        // When the file is written for another reason, they're still there.
        val written = load(text.replace("  enabled: true\n", "")).write!!
        assertTrue("  limt: 5\n" in written, written)
        assertTrue("extra:\n  nested: [1, \"a b\"]\n" in written, written)
        assertEquals(emptyList(), load(written).notices)
    }

    @Test
    fun missingSettingsAreAddedInSchemaOrderWithTheirComments() {
        val text = """
            homes:
              limit: 7
              cost: 1
            language: fr
        """.trimIndent()
        val load = load(text)
        assertEquals(ConfigNotice.Kind.ADDED, load.notices.single().kind)
        assertEquals(
            listOf("storage", "homes.teleport-delay", "homes.enabled", "homes.blocked-worlds", "homes.slots", "messages"),
            load.notices.single().paths,
        )
        val written = load.write!!
        assertEquals(DEFAULT_FILE.replace("limit: 3", "limit: 7").replace("cost: 2.5", "cost: 1").replace("language: \"auto\"", "language: \"fr\""), written)
        // Written once, then left alone.
        val again = load(written)
        assertNull(again.write)
        assertEquals(load.config[HomesConfig.homes.limit], again.config[HomesConfig.homes.limit])
    }

    @Test
    fun aFileWithoutAVersionIsGivenOne() {
        val load = load(DEFAULT_FILE.lines().filter { !it.startsWith("config-version") }.joinToString("\n"))
        assertEquals(DEFAULT_FILE, load.write)
        assertEquals(0, load.carriedFrom)
    }

    @Test
    fun aSectionThatIsntOneIsReportedAndKept() {
        val load = load(DEFAULT_FILE.replace(Regex("messages:\n(  .*\n)+"), "messages: oops\n").replace("  enabled: true\n", ""))
        val problem = load.problems.single()
        assertEquals("messages (\"oops\") should be a section of settings; using the defaults for this section.", problem.message)
        assertEquals(HomesConfig.messages.welcome.default, load.config[HomesConfig.messages.welcome])
        assertTrue("messages: \"oops\"\n" in load.write!!)
    }

    @Test
    fun anOldFileIsCarriedForwardAndUntouchedDefaultsFollowTheUpdate() {
        val old = """
            config-version: 1
            home-limit: 5
            delay: 10s
            language: en
            messages:
              welcome: "<gray>Welcome home, <player>."
        """.trimIndent()
        val load = load(old)
        assertEquals(1, load.carriedFrom)
        assertTrue(load.backup)
        assertEquals(5, load.config[HomesConfig.homes.limit])
        assertEquals(10.seconds, load.config[HomesConfig.homes.delay])
        assertEquals("<green>Welcome home, <player>!", load.config[HomesConfig.messages.welcome].source)
        assertEquals(
            listOf(ConfigNotice.Kind.REFRESHED, ConfigNotice.Kind.CARRIED_FORWARD, ConfigNotice.Kind.ADDED),
            load.notices.map { it.kind },
        )
        assertEquals(listOf("messages.welcome"), load.notices[0].paths)
        assertEquals(emptyList(), load.problems)
        val written = load.write!!
        assertTrue(written.startsWith("# xHomes\n"), written)
        assertTrue("config-version: 2\n" in written)
        assertTrue("home-limit" !in written && "\ndelay" !in written, written)
        // The carried file reads back as it is.
        val again = load(written)
        assertNull(again.write)
        assertEquals(emptyList(), again.problems)
    }

    @Test
    fun aMessageItsOwnerChangedIsKeptWhenCarriedForward() {
        val load = load("config-version: 1\nmessages:\n  welcome: \"<gold>Home!\"\n")
        assertEquals("<gold>Home!", load.config[HomesConfig.messages.welcome].source)
        assertTrue(load.notices.none { it.kind == ConfigNotice.Kind.REFRESHED })
    }

    @Test
    fun aMigrationCanRunAgainSafely() {
        // Carried once (homes.limit set, home-limit gone), then carried again: as when a carry
        // couldn't be saved and runs on the next start, or an owner put the old key back.
        val once = load("config-version: 1\nhome-limit: 5\n").write!!
        val twice = load(once.replace("config-version: 2", "config-version: 1"))
        assertEquals(5, twice.config[HomesConfig.homes.limit])
        assertEquals(once, twice.write)
        // The owner's value at the new place wins over a leftover old key.
        val both = load("config-version: 1\nhome-limit: 5\nhomes:\n  limit: 8\n")
        assertEquals(8, both.config[HomesConfig.homes.limit])
        assertTrue("home-limit" !in both.write!!)
    }

    @Test
    fun aNewerFileIsReadButNeverWritten() {
        val load = load("config-version: 3\nlanguage: de\nfuture: true\n")
        assertEquals("de", load.config[HomesConfig.language])
        assertNull(load.write)
        assertEquals(ConfigProblem.Kind.NEWER_VERSION, load.problems.first().kind)
        assertEquals(1, load.problems.first().line)
    }

    @Test
    fun aVersionThatIsntANumberIsReadAsCurrent() {
        val load = load(DEFAULT_FILE.replace("config-version: 2", "config-version: two"))
        assertEquals(listOf(ConfigProblem.Kind.BAD_VERSION), kinds(load))
        assertNull(load.write)
    }

    @Test
    fun aFileThatIsntYamlIsAnErrorWithItsLine() {
        val error = assertIs<Outcome.Err<ConfigError>>(Configs.load(HomesConfig, "language: en\nhomes\n  limit: 3\n", TestYaml)).error
        assertEquals(2, error.line)
        assertTrue(error.message.startsWith("The config isn't valid YAML"))
        assertIs<Outcome.Err<ConfigError>>(Configs.load(HomesConfig, "- a\n- b\n", TestYaml))
    }

    @Test
    fun anEmptyFileGetsEverySetting() {
        val load = load("# nothing yet\n")
        assertEquals(DEFAULT_FILE, load.write)
    }

    @Test
    fun messagesAreParsedWithTheThemesTags() {
        val tags = mapOf("highlight" to MarkupTag.Styling(Style(bold = true)))
        val load = load(DEFAULT_FILE.replace("<green>Welcome home", "<highlight>Welcome</highlight> home"), ConfigContext(tags))
        val welcome = load.config[HomesConfig.messages.welcome].render()
        assertEquals("Welcome home, <player>!", welcome.plain())
        fun all(c: co.xenastudios.core.common.text.Component): List<co.xenastudios.core.common.text.Component> = listOf(c) + c.children.flatMap { all(it) }
        assertTrue(all(welcome).any { it.plain() == "Welcome" && it.style.bold == true }, welcome.toString())
    }

    @Test
    fun textRoundTripsThroughQuotes() {
        val text = "a \"quoted\" \\ line\nand a tab\there: #not a comment"
        val edited = assertIs<Outcome.Ok<String?>>(Configs.edit(HomesConfig, DEFAULT_FILE, TestYaml) { set(HomesConfig.language, text) }).value!!
        assertEquals(text, load(edited).config[HomesConfig.language])
    }

    @Test
    fun anEditChangesOneSettingAndKeepsTheRest() {
        val text = DEFAULT_FILE.replace("limit: 3", "limit: 9") + "\nextra: 1\n"
        val edited = assertIs<Outcome.Ok<String?>>(
            Configs.edit(HomesConfig, text, TestYaml) {
                assertEquals(9, this[HomesConfig.homes.limit])
                this[HomesConfig.homes.blockedWorlds] = this[HomesConfig.homes.blockedWorlds].orEmpty() + "the_end"
            },
        ).value!!
        assertEquals(text.replace("[\"world_nether\"]", "[\"world_nether\", \"the_end\"]"), edited)
        // Setting what's already there changes nothing.
        assertNull(assertIs<Outcome.Ok<String?>>(Configs.edit(HomesConfig, text, TestYaml) { set(HomesConfig.homes.limit, 9) }).value)
        // Reset takes the owner's value out, so the default is written.
        val reset = assertIs<Outcome.Ok<String?>>(Configs.edit(HomesConfig, text, TestYaml) { reset(HomesConfig.homes.limit) }).value!!
        assertTrue("limit: 3\n" in reset)
    }

    @Test
    fun anEditRefusesWhatTheSettingDoesnt() {
        val error = assertIs<Outcome.Err<ConfigError>>(Configs.edit(HomesConfig, DEFAULT_FILE, TestYaml) { set(HomesConfig.homes.limit, 101) }).error
        assertEquals("homes.limit can't be 101: it must be from 0 to 100.", error.message)
        assertIs<Outcome.Err<ConfigError>>(Configs.edit(HomesConfig, "config-version: 9\n", TestYaml) { set(HomesConfig.homes.limit, 1) })
        assertIs<Outcome.Err<ConfigError>>(Configs.edit(HomesConfig, "limit: [", TestYaml) { set(HomesConfig.homes.limit, 1) })
    }

    @Test
    fun theSchemaIsDataForTheDocs() {
        val description = HomesConfig.describe()
        assertEquals(2, description.version)
        assertEquals(listOf("language", "storage", "homes", "messages"), description.entries.map { it.path })
        val storage = assertIs<ConfigDescription.Setting>(description.entries[1])
        assertEquals("enum", storage.type)
        assertEquals(listOf("sqlite", "mysql", "flat-file"), storage.choices)
        assertEquals("\"sqlite\"", storage.defaultText)
        val homes = assertIs<ConfigDescription.Section>(description.entries[2])
        assertEquals(listOf("Setting and going to homes."), homes.comment)
        val limit = assertIs<ConfigDescription.Setting>(homes.entries[0])
        assertEquals("homes.limit", limit.path)
        assertEquals("integer", limit.type)
        assertEquals("3", limit.defaultText)
        assertEquals(0.0, limit.min)
        assertEquals(100.0, limit.max)
        val delay = assertIs<ConfigDescription.Setting>(homes.entries[1])
        assertEquals("\"1m30s\"", delay.defaultText)
        assertEquals("list<integer>", assertIs<ConfigDescription.Setting>(homes.entries[5]).type)
        val welcome = assertIs<ConfigDescription.Setting>(assertIs<ConfigDescription.Section>(description.entries[3]).entries[0])
        assertEquals(listOf<YamlNode>(YamlNode.Scalar("<gray>Welcome home, <player>.")), welcome.formerDefaults)
    }

    @Test
    fun aSchemaMistakeFailsWhereItsDeclared() {
        assertFailsWith<IllegalArgumentException> { object : ConfigSchema() { val a = string("a", ""); val b = string("a", "") } }
        assertFailsWith<IllegalArgumentException> { object : ConfigSchema() { val a = string("a.b", "") } }
        assertFailsWith<IllegalArgumentException> { object : ConfigSchema() { val v = int("config-version", 1) } }
        assertFailsWith<IllegalArgumentException> { object : ConfigSchema() { val a = int("a", 5, max = 3) } }
        val unmigrated = object : ConfigSchema(3) {
            val a = string("a", "")
            init { migrate(1) {} }
        }
        assertFailsWith<IllegalStateException> { Configs.load(unmigrated, "config-version: 1\n", TestYaml) }
    }

    @Test
    fun migrationOperationsCanRunTwice() {
        val root = YamlNode.Mapping(listOf(YamlNode.Mapping.Entry("a", YamlNode.Scalar("1"))))
        val scope = MigrationScope(root)
        repeat(2) {
            scope.move("a", "b.c")
            scope["d"] = "x"
            scope.remove("gone")
            scope.change("d") { YamlNode.Scalar("y") }
        }
        assertEquals(YamlNode.Scalar("1"), scope["b.c"])
        assertNull(scope["a"])
        assertEquals(YamlNode.Scalar("y"), scope["d"])
    }

    private fun lineOf(text: String, needle: String) = text.lines().indexOfFirst { needle in it } + 1

    private companion object {
        val DEFAULT_FILE = """
            # xHomes
            # Docs: https://plugins.xenastudios.co/xhomes/

            # Managed by the plugin, to carry your settings forward on updates. Don't edit it.
            config-version: 2

            # The language players see: auto, or a code like en.
            language: "auto"

            # Where homes are kept.
            storage: "sqlite"

            # Setting and going to homes.
            homes:
              # How many homes a player can set.
              limit: 3
              # How long a player stands still first.
              teleport-delay: "1m30s"
              enabled: true
              cost: 2.5
              # Worlds homes can't be set in.
              blocked-worlds: ["world_nether"]
              slots: [1, 2, 3]

            messages:
              welcome: "<green>Welcome home, <player>!"
              # Put before every message. Empty for none.
              prefix: ""
        """.trimIndent() + "\n"
    }
}
