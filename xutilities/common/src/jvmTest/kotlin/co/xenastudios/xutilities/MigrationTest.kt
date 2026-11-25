package co.xenastudios.xutilities

import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.i18n.LanguageChoice
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.family.Shareable
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.testing.FakeFamily
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xutilities.config.LinkCommand
import co.xenastudios.xutilities.config.XUtilitiesConfig
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
 * does: 1.0.1's default file exactly as it shipped, and 2.1.0's with an owner's edits.
 */
class MigrationTest {
    private val dir: Path = NioFiles.createTempDirectory("xutilities")
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
    fun aOnePointZeroConfigCarriesForwardCleanly() {
        write("config.yml", fixture("config-1.0.1.yml").replace("max: 10", "max: 7").replace("https://discord.gg/example", "https://discord.gg/mine"))
        val loaded = load()
        assertEquals(emptyList(), loaded.problems, "1.0.1's settings are all valid in 3.0")
        val config = loaded.config
        assertEquals(7, config[XUtilitiesConfig.commands.speed.max])
        assertTrue("discord.gg/mine" in config[XUtilitiesConfig.infoCommands].getValue("discord").message)
        // An example link the owner never touched gets the new look.
        assertEquals(LinkCommand.DEFAULTS["store"]?.message, config[XUtilitiesConfig.infoCommands].getValue("store").message)
        assertEquals(listOf("gms", "gm0"), config[XUtilitiesConfig.commands.survival.aliases])
        // Every message was 1.0.1's own, so none is carried: lang/en.yml is 3.0's, and the language follows the family.
        assertEquals(Shareable.Shared, config[XUtilitiesConfig.messages.language])
        assertFalse("Your messages from" in read("lang/en.yml"))
        assertTrue(NioFiles.exists(dir.resolve("config.yml.bak-v1")))
        val migrated = read("config.yml")
        assertTrue("config-version: 3" in migrated)
        assertFalse("message-self" in migrated, "messages are in lang/ now")
        assertFalse("disabled: \"<red>This command is disabled." in migrated)
        assertTrue("stonecutter:" in migrated && "updates:" in migrated && "update-checker:" !in migrated)
        assertTrue(migrated.startsWith("# xUtilities: every command you need."))

        // Loading again changes nothing.
        log.clear()
        val again = load()
        assertEquals(emptyList(), again.problems)
        assertEquals(config, again.config)
        assertEquals(migrated, read("config.yml"))
    }

    @Test
    fun aTwoPointOneConfigKeepsItsEditedMessagesInEnglishsFile() {
        val edited = fixture("config-2.1.0.yml")
            .replace("message-self: \"<prefix>You've been <success>healed</success>.\"", "message-self: \"<gold>Patched up!\"")
            .replace("player-not-found: \"<prefix><error>No player named <highlight><player></highlight> is online.\"", "player-not-found: \"<red>Who is <player>?\"")
            .replace("highlight: \"<#B48CFF>\"", "highlight: \"<gold>\"")
            .replace("aliases: [\"fix\"]", "aliases: [\"fix\", \"mend\"]")
            .replace("update-checker:\n  # Ask Modrinth", "update-checker:\n  # Ask Modrinth").replace(Regex("(update-checker:\\n(?:  #.*\\n)*  enabled: )true"), "$1false")
        write("config.yml", edited)
        val loaded = load()
        assertEquals(emptyList(), loaded.problems)
        val config = loaded.config
        assertEquals(listOf("fix", "mend"), config[XUtilitiesConfig.commands.repair.aliases])
        // 2.x's update-checker.enabled: false is updates.check: false.
        assertEquals(false, config[XUtilitiesConfig.updates.check])
        // The edited messages are in lang/en.yml, the rest are 3.0's.
        val english = read("lang/en.yml")
        assertTrue("heal.self: \"<gold>Patched up!\"" in english, english)
        assertTrue("core.command.unknown-player: \"<red>Who is <player>?\"" in english, english)
        assertTrue("fly.on-self: \"<prefix>Flight <success><state></success>.\"" in english, english)
        val en = loaded.translations.language(LanguageTag.ENGLISH)!!
        assertEquals("Patched up!", loaded.translations.render(XUtilitiesMessages.healSelf, en)?.plain())
        assertEquals("Who is Nobody?", loaded.translations.render(CoreMessages.commandUnknownPlayer, en, mapOf("player" to co.xenastudios.core.common.text.Placeholder.text("Nobody")))?.plain())
        // Edited messages keep every player on them: this plugin reads English, as 2.x did.
        assertEquals(Shareable.Own(LanguageChoice.Fixed(LanguageTag.ENGLISH)), config[XUtilitiesConfig.messages.language])
        // The theme colour the owner changed is theirs; the rest follow the family.
        val migrated = read("config.yml")
        assertTrue("highlight: \"<gold>\"" in migrated, migrated)
        assertTrue("prefix: \"shared\"" in migrated, migrated)
        assertEquals(fixture("config-2.1.0.yml").replace("aliases: [\"fix\"]", "aliases: [\"fix\", \"mend\"]").let { edited }, read("config.yml.bak-v2"))
        assertTrue(log.lines.any { "Copied 2 messages" in it.message }, log.lines.toString())
    }

    @Test
    fun anUntouchedTwoPointOneConfigCarriesNoMessages() {
        write("config.yml", fixture("config-2.1.0.yml"))
        val loaded = load()
        assertEquals(emptyList(), loaded.problems)
        assertEquals(Shareable.Shared, loaded.config[XUtilitiesConfig.messages.language])
        assertEquals(XUtilitiesConfig.defaults, loaded.config)
        assertTrue(NioFiles.exists(dir.resolve("config.yml.bak-v2")))
        // Every language the jar ships is written.
        for (tag in listOf("en", "de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN")) assertTrue(NioFiles.exists(dir.resolve("lang/$tag.yml")), tag)
    }

    @Test
    fun aBrokenLinkCommandIsSkippedAndSaysWhy() {
        write("config.yml", "info-commands:\n  vote:\n    message: \"Vote!\"\n  bad:\n    aliases: [\"no spaces allowed\"]\n    message: \"x\"\n")
        val loaded = load()
        assertEquals(setOf("vote"), loaded.config[XUtilitiesConfig.infoCommands].keys)
        assertTrue(loaded.problems.any { it.startsWith("info-commands.bad") }, loaded.problems.toString())
    }

    @Test
    fun aConfigThatIsntYamlKeepsTheLastGoodOne() {
        val files = JvmFiles(dir, family, emptyMap(), log)
        write("config.yml", "commands:\n  fly:\n    enabled: false\n")
        val first = files.load()
        write("config.yml", "commands: [\n")
        val second = files.load()
        assertEquals(first.config, second.config)
        assertTrue(second.problems.any { "keeping the settings from before" in it }, second.problems.toString())
    }
}
