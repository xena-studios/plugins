package co.xenastudios.core.common.family

import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigProblem
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.i18n.LanguageChoice
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.common.text.Theme
import co.xenastudios.core.testing.FakeFamily
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.testing.ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A plugin's config with the family's sections, as each plugin will declare them. */
object BansConfig : ConfigSchema(1) {
    val messages: MessagesSection = MessagesSection(this)
    val storage: StorageSection = StorageSection(this, "xbans_")
    val network: NetworkSection = NetworkSection(this)
    val limit: ConfigKey<Int> = int("limit", 3)
}

class FamilyTest {
    private fun own(text: String) = Configs.load(BansConfig, text, TestYaml).ok().config

    @Test
    fun theFirstPluginToStartWritesTheDefaults() {
        val server = FakeFamily()
        val first = server.family().load()
        assertNull(first.error)
        val written = assertNotNull(server.folder.readText(Family.FILE).ok())
        assertEquals(Configs.defaultText(FamilyConfig), written)
        assertTrue("  language: \"auto\"" in written, written)
        assertTrue("    prefix: \"<#B48CFF>»</#B48CFF> \"" in written, written)

        // The next plugin reads it and writes nothing.
        server.folder.writeText(Family.FILE, written.replace("language: \"auto\"", "language: de"))
        val second = server.family()
        second.load()
        assertEquals(LanguageChoice.Fixed(LanguageTag.of("de")), second.look.languages.choice)
        assertEquals(written.replace("language: \"auto\"", "language: de"), server.folder.readText(Family.FILE).ok())
    }

    @Test
    fun aReloadPicksUpChangesAndKeepsTheLastGoodSettings() {
        val server = FakeFamily()
        val family = server.family()
        family.load()
        val text = server.folder.readText(Family.FILE).ok()!!
        server.folder.writeText(Family.FILE, text.replace("highlight: \"<#b48cff>\"", "highlight: gold bold"))
        assertNull(family.load().error)
        assertEquals(Style(color = TextColor.NAMED.getValue("gold"), bold = true), family.look.theme.highlight)

        server.folder.writeText(Family.FILE, "messages: [broken")
        val broken = family.load()
        assertNotNull(broken.error)
        assertEquals(Style(color = TextColor.NAMED.getValue("gold"), bold = true), family.look.theme.highlight, "the last good settings")
    }

    @Test
    fun aPluginTakesTheFamilysLookUnlessItSetsItsOwn() {
        val server = FakeFamily()
        val family = server.family()
        server.folder.writeText(Family.FILE, "messages:\n  language: de\n  theme:\n    prefix: \"[Net] \"\n")
        family.load()

        val shared = family.look(BansConfig.defaults, BansConfig.messages)
        assertEquals(LanguageChoice.Fixed(LanguageTag.of("de")), shared.languages.choice)
        assertEquals("[Net] ", shared.theme.prefix)
        assertEquals(Theme.DEFAULT_ERROR, shared.theme.error)

        val own = family.look(own("messages:\n  language: auto\n  theme:\n    prefix: \"[Bans] \"\n    error: red\n"), BansConfig.messages)
        assertEquals(LanguageChoice.Auto, own.languages.choice)
        assertEquals("[Bans] ", own.theme.prefix)
        assertEquals(Style(color = TextColor.NAMED.getValue("red")), own.theme.error)
        assertEquals(LanguageTag.ENGLISH, own.languages.default, "default-language is still shared")
    }

    @Test
    fun aPluginsDefaultFileLeavesItToTheFamily() {
        val text = Configs.defaultText(BansConfig)
        assertTrue("  language: \"shared\"\n" in text, text)
        assertTrue("    highlight: \"shared\"\n" in text, text)
        assertTrue("  type: \"sqlite\"\n" in text, text)
        assertTrue("  table-prefix: \"xbans_\"\n" in text, text)
        assertTrue("  check-every: \"3s\"\n" in text, text)
    }

    @Test
    fun theFamilysFileCantSayShared() {
        val load = Configs.load(FamilyConfig, "messages:\n  language: shared\n", TestYaml).ok()
        assertEquals(ConfigProblem.Kind.BAD_VALUE, load.problems.single().kind)
        assertEquals(LanguageChoice.Auto, FamilyConfig.messages.look(load.config).languages.choice)
    }

    @Test
    fun aBadStyleIsReportedAndTheFamilysUsed() {
        val load = Configs.load(BansConfig, "messages:\n  theme:\n    success: <gradient:red:blue>\n", TestYaml).ok()
        assertEquals("messages.theme.success", load.problems.single().path)
        assertEquals(Shareable.Shared, load.config[BansConfig.messages.theme.success])
    }

    @Test
    fun storageIsThePluginsOwnOrTheFamilys() {
        val sqlite = BansConfig.storage.plan(BansConfig.defaults, FamilyDatabase.NONE)
        assertEquals(DatabaseSettings(DatabaseSettings.Type.SQLITE, "xbans_"), sqlite.database)
        assertFalse(sqlite.inFamilyFolder)

        val mysql = own("storage:\n  type: mysql\n  mysql:\n    host: db\n    password: secret\n  pool:\n    readers: 4\n")
        val plan = BansConfig.storage.plan(mysql, FamilyDatabase.NONE)
        assertEquals(DatabaseSettings.Type.MYSQL, plan.database.type)
        assertEquals("db", plan.database.mysql.host)
        assertEquals("secret", plan.database.mysql.password)
        assertEquals(4, plan.database.readers)

        val shared = own("storage:\n  type: shared\n  table-prefix: bans_\n")
        val none = BansConfig.storage.plan(shared, FamilyDatabase.NONE)
        assertEquals(DatabaseSettings.Type.SQLITE, none.database.type)
        assertNotNull(none.problem)

        val server = FakeFamily()
        server.folder.writeText(Family.FILE, "database:\n  type: sqlite\n")
        val family = server.family()
        family.load()
        val inFamily = family.storage(shared, BansConfig.storage)
        assertEquals(DatabaseSettings(DatabaseSettings.Type.SQLITE, "bans_"), inFamily.database)
        assertTrue(inFamily.inFamilyFolder, "plugins/Xena/xena.db")
        assertNull(inFamily.problem)

        server.folder.writeText(Family.FILE, "database:\n  type: mysql\n  mysql:\n    host: net\n")
        family.load()
        val onNetwork = family.storage(shared, BansConfig.storage)
        assertEquals("net", onNetwork.database.mysql.host)
        assertEquals("bans_", onNetwork.database.prefix)
        assertFalse(onNetwork.inFamilyFolder)
    }

    @Test
    fun aBadTablePrefixIsRefused() {
        val load = Configs.load(BansConfig, "storage:\n  table-prefix: Bans-\n", TestYaml).ok()
        assertEquals("storage.table-prefix", load.problems.single().path)
        assertEquals("xbans_", load.config[BansConfig.storage.tablePrefix])
    }

    @Test
    fun networkSettingsAreTyped() {
        val config = own("network:\n  enabled: true\n  check-every: 5s\n")
        val settings = BansConfig.network.read(config)
        assertTrue(settings.enabled)
        assertEquals(5.seconds, settings.checkEvery)
        val slow = Configs.load(BansConfig, "network:\n  check-every: 5m\n", TestYaml).ok()
        assertEquals("network.check-every", slow.problems.single().path)
    }

    @Test
    fun stylesAreWrittenAsTheTagsThatReadBack() {
        val style = Style(color = TextColor(0xB48CFF), bold = true, italic = true)
        assertEquals("<#b48cff><bold><italic>", ThemeSection.tags(style))
        assertEquals(style, Theme.style(ThemeSection.tags(style)).let { (it as co.xenastudios.core.common.Outcome.Ok).value })
        assertEquals("<gray>", ThemeSection.tags(Theme.DEFAULT_BASE))
    }
}
