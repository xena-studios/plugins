package co.xenastudios.xlimbo

import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.Shareable
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.LanguageChoice
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.testing.FakeFamily
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xlimbo.api.ReturnRoute
import co.xenastudios.xlimbo.config.XLimboConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files as NioFiles
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real configs from earlier releases, carried forward to 3.0 on disk as a server's first start
 * does: 1.0.1's default file exactly as it shipped, and 2.1.0's (its default file, from the docs it
 * generated) with an owner's edits.
 */
class MigrationTest {
    private val dir: Path = NioFiles.createTempDirectory("xlimbo")
    private val log = RecordingLog()
    private val family: Family = FakeFamily().family(JvmYaml)

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun fixture(name: String): String = javaClass.getResourceAsStream("/$name")!!.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    private fun write(name: String, text: String) {
        NioFiles.write(dir.resolve(name), text.toByteArray(StandardCharsets.UTF_8))
    }
    private fun read(name: String): String = String(NioFiles.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8)
    private fun load(): Loaded = JvmFiles(dir, family, LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES), log).load()

    @Test
    fun aOnePointZeroConfigCarriesForward() {
        write(
            "config.yml",
            fixture("config-1.0.1.yml").replace("floor-y: 64", "floor-y: 100").replace("main-server: \"main\"", "main-server: \"hub\"")
                .replace("<red>Please wait <yellow><seconds></yellow>s before trying again.</red>", "<gold>Slow down!"),
        )
        val loaded = load()
        assertEquals(emptyList(), loaded.problems, "1.0.1's settings are all valid in 3.0")
        val config = loaded.config
        assertEquals(100, config[XLimboConfig.world.floorY])
        assertEquals("purple_stained_glass", config[XLimboConfig.world.floorBlock], "1.x's plain glass gets the new purple")
        assertEquals("hub", config[XLimboConfig.mainServer])
        assertEquals(64, config[XLimboConfig.world.border.spawnRadius], "1.x's 4000-block spread is narrowed")
        assertEquals(ReturnRoute.PROXY, config[XLimboConfig.sendBack.via], "a 1.x limbo is a fallback server, as it was")
        val english = read("lang/en.yml")
        assertTrue("join-command.cooldown: \"<gold>Slow down!\"" in english, english)
        // An untouched 1.x message gets 3.0's.
        assertTrue("join-command.sending: \"<prefix>Connecting you to <highlight><server></highlight>...\"" in english, english)
        assertEquals(Shareable.Own(LanguageChoice.Fixed(LanguageTag.ENGLISH)), config[XLimboConfig.messages.language])
        assertTrue(NioFiles.exists(dir.resolve("config.yml.bak-v1")))
        val migrated = read("config.yml")
        assertTrue("config-version: 3" in migrated, migrated)
        assertTrue("void:" in migrated, "1.x's protections.void key is kept")
        assertFalse("message-cooldown" in migrated, "messages are in lang/ now")
        assertTrue(migrated.startsWith("# xLimbo: never drop a player again."))

        log.clear()
        val again = load()
        assertEquals(emptyList(), again.problems)
        assertEquals(config, again.config, "loading again changes nothing")
        assertEquals(migrated, read("config.yml"))
    }

    @Test
    fun aTwoPointOneConfigKeepsEverySettingAndItsEditedMessages() {
        val edited = fixture("config-2.1.0.yml")
            .replace("custom-join-message: \"<prefix>The server is unavailable right now, so you're waiting here. Type <highlight>/join</highlight> to go back.\"", "custom-join-message: \"<gold>Hold tight!\"")
            .replace("player-only: \"<prefix><error>Only players can use this command.\"", "player-only: \"<red>Players only.\"")
            .replace("highlight: \"<#B48CFF>\"", "highlight: \"<gold>\"")
            .replace("aliases: [\"server\", \"lobby\"]", "aliases: [\"server\", \"lobby\", \"hub\"]")
            .replace("  enabled: false\n  # Your main server's address", "  enabled: true\n  # Your main server's address")
            .replace("floor-block: \"purple_stained_glass\"", "floor-block: \"glass\"")
            .replace(Regex("(update-checker:\\n(?:  #.*\\n)*  enabled: )true"), "$1false")
        write("config.yml", edited)
        val loaded = load()
        assertEquals(emptyList(), loaded.problems)
        val config = loaded.config
        assertEquals(listOf("server", "lobby", "hub"), config[XLimboConfig.joinCommand.aliases])
        assertEquals(true, config[XLimboConfig.serverCheck.enabled])
        assertEquals("glass", config[XLimboConfig.world.floorBlock], "a block a 2.x owner chose is kept, plain glass too")
        // 2.x's update-checker.enabled: false is updates.check: false.
        assertEquals(false, config[XLimboConfig.updates.check])
        val english = read("lang/en.yml")
        assertTrue("join.welcome: \"<gold>Hold tight!\"" in english, english)
        assertTrue("core.command.players-only: \"<red>Players only.\"" in english, english)
        val en = loaded.translations.language(LanguageTag.ENGLISH)!!
        assertEquals("Hold tight!", loaded.translations.render(XLimboMessages.welcome, en)?.plain())
        assertEquals("Players only.", loaded.translations.render(CoreMessages.commandPlayersOnly, en)?.plain())
        assertEquals(Shareable.Own(LanguageChoice.Fixed(LanguageTag.ENGLISH)), config[XLimboConfig.messages.language])
        val migrated = read("config.yml")
        assertTrue("highlight: \"<gold>\"" in migrated, migrated)
        assertFalse("custom-join-message" in migrated || "update-checker" in migrated || "\ntheme:" in migrated, migrated)
        assertEquals(edited, read("config.yml.bak-v2"))
        assertTrue(log.lines.any { "Copied 2 messages" in it.message }, log.lines.toString())
    }

    @Test
    fun anUntouchedTwoPointOneConfigCarriesNoMessages() {
        write("config.yml", fixture("config-2.1.0.yml"))
        val loaded = load()
        assertEquals(emptyList(), loaded.problems)
        assertEquals(Shareable.Shared, loaded.config[XLimboConfig.messages.language])
        assertEquals(XLimboConfig.defaults, loaded.config)
        assertTrue(NioFiles.exists(dir.resolve("config.yml.bak-v2")))
        // Every language the jar ships is written.
        for (tag in listOf("en", "de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN")) assertTrue(NioFiles.exists(dir.resolve("lang/$tag.yml")), tag)
        // What 2.1.0 reads when it's put back: every setting at its 2.x path.
        val migrated = read("config.yml")
        for (path in listOf("world:", "  floor-block: \"purple_stained_glass\"", "main-server: \"main\"", "join-command:", "  aliases: [\"server\", \"lobby\"]", "protections:", "  build-guard:")) {
            assertTrue(path in migrated, path)
        }
    }

    @Test
    fun aBadBlockOrDestinationFallsBackToItsDefault() {
        write("config.yml", "world:\n  floor-block: \"not a block\"\nsend-back:\n  via: teleport\n  destination: \"../x\"\n")
        val loaded = load()
        assertEquals("purple_stained_glass", loaded.config[XLimboConfig.world.floorBlock])
        assertEquals("previous", loaded.config[XLimboConfig.sendBack.destination])
        assertEquals(ReturnRoute.TELEPORT, loaded.config[XLimboConfig.sendBack.via])
        assertEquals(2, loaded.problems.size, loaded.problems.toString())
    }

    @Test
    fun aConfigThatIsntYamlKeepsTheLastGoodOne() {
        val files = JvmFiles(dir, family, emptyMap(), log)
        write("config.yml", "main-server: \"hub\"\n")
        val first = files.load()
        write("config.yml", "world: [\n")
        val second = files.load()
        assertEquals(first.config, second.config)
        assertTrue(second.problems.any { "keeping the settings from before" in it }, second.problems.toString())
    }
}
