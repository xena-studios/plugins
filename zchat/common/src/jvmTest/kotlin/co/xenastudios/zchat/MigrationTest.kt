package co.xenastudios.zchat

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.Shareable
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.LanguageChoice
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.testing.FakeFamily
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.zchat.config.Group
import co.xenastudios.zchat.config.ZChatConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files as NioFiles

/**
 * Real configs from earlier releases, carried forward to 4.0 on disk as a server's first start
 * does: 2.0.0's default file exactly as it shipped, and 3.2.0's, untouched and with an owner's edits.
 */
class MigrationTest {
    private val dir: Path = NioFiles.createTempDirectory("zchat")
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
    private fun files() = JvmFiles(dir, family, LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES), log)

    @Test
    fun anUntouchedThreePointTwoConfigCarriesNoMessages() {
        write("config.yml", fixture("config-3.2.0.yml"))
        val loaded = files().load()
        assertEquals(emptyList(), loaded.problems)
        assertEquals(ZChatConfig.defaults, loaded.config, "every 3.2 setting is 4.0's default")
        assertEquals(Shareable.Shared, loaded.config[ZChatConfig.messages.language])
        assertTrue(NioFiles.exists(dir.resolve("config.yml.bak-v2")))
        val migrated = read("config.yml")
        assertTrue("config-version: 3" in migrated)
        assertFalse("message-blocked" in migrated || "message-cleared" in migrated, "messages are in lang/ now")
        assertFalse("update-checker:" in migrated)
        assertTrue("updates:" in migrated && "default-format:" in migrated && "socialspy:" in migrated)
        for (tag in listOf("en", "de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN")) assertTrue(NioFiles.exists(dir.resolve("lang/$tag.yml")), tag)
        assertFalse("Your messages from" in read("lang/en.yml"))

        // Loading again changes nothing.
        val again = files().load()
        assertEquals(emptyList(), again.problems)
        assertEquals(loaded.config, again.config)
        assertEquals(migrated, read("config.yml"))
    }

    @Test
    fun aThreePointTwoConfigKeepsItsEditedMessagesInEnglishsFile() {
        val edited = fixture("config-3.2.0.yml")
            .replace("message: \"<prefix><error>Wait <highlight><seconds>s</highlight> before chatting again.\"", "message: \"<gold>Slow down, <seconds>s!\"")
            .replace("player-not-found: \"<prefix><error>No player named <highlight><player></highlight> is online.\"", "player-not-found: \"<red>Who is <player>?\"")
            .replace("highlight: \"<#B48CFF>\"", "highlight: \"<gold>\"")
            .replace("aliases: [\"cc\"]", "aliases: [\"cc\", \"wipe\"]")
            .replace("words: [\"badword1\", \"badword2\"]", "words: [\"creeper*\"]")
            .replace(Regex("(update-checker:\\n(?:  #.*\\n)*  enabled: )true"), "$1false")
        write("config.yml", edited)
        val loaded = files().load()
        assertEquals(emptyList(), loaded.problems)
        val config = loaded.config
        assertEquals(listOf("cc", "wipe"), config[ZChatConfig.clearchat.aliases])
        assertEquals(listOf("creeper*"), config[ZChatConfig.filter.words])
        assertEquals(false, config[ZChatConfig.updates.check], "3.x's update-checker.enabled: false is updates.check: false")
        val english = read("lang/en.yml")
        assertTrue("cooldown.wait: \"<gold>Slow down, <seconds>s!\"" in english, english)
        assertTrue("core.command.unknown-player: \"<red>Who is <player>?\"" in english, english)
        val en = loaded.translations.language(LanguageTag.ENGLISH)!!
        assertEquals("Slow down, 3s!", loaded.translations.render(ZChatMessages.cooldownWait, en, mapOf("seconds" to co.xenastudios.core.common.text.Placeholder.text("3")))?.plain())
        assertEquals("Who is Nobody?", loaded.translations.render(CoreMessages.commandUnknownPlayer, en, mapOf("player" to co.xenastudios.core.common.text.Placeholder.text("Nobody")))?.plain())
        // Edited messages keep every player on them: zChat reads English, as 3.x did.
        assertEquals(Shareable.Own(LanguageChoice.Fixed(LanguageTag.ENGLISH)), config[ZChatConfig.messages.language])
        val migrated = read("config.yml")
        assertTrue("highlight: \"<gold>\"" in migrated, migrated)
        assertTrue("prefix: \"shared\"" in migrated, migrated)
        assertEquals(edited, read("config.yml.bak-v2"), "3.2 put back reads its own copy")
        assertTrue(log.lines.any { "Copied 2 messages" in it.message }, log.lines.toString())
    }

    @Test
    fun aTwoPointZeroConfigCarriesForward() {
        write("config.yml", fixture("config-2.0.0.yml"))
        val loaded = files().load()
        assertEquals(emptyList(), loaded.problems)
        val config = loaded.config
        assertEquals(emptyList(), config[ZChatConfig.filter.patterns], "2.0's example patterns were placeholders")
        assertEquals(3.0, config[ZChatConfig.cooldown.seconds])
        // 2.0's shipped messages aren't carried: they get 4.0's.
        assertFalse("Your messages from" in read("lang/en.yml"), read("lang/en.yml"))
        assertTrue(NioFiles.exists(dir.resolve("config.yml.bak-v1")))
        assertTrue("config-version: 3" in read("config.yml"))
    }

    @Test
    fun aZChatOneConfigIsSetAside() {
        write("config.yml", "chat-format: \"{player}: {message}\"\nbanned-words:\n  - foo\n")
        val loaded = files().load()
        assertEquals(ZChatConfig.defaults, loaded.config)
        assertTrue(NioFiles.exists(dir.resolve("config.yml.old")))
        assertTrue("config-version: 3" in read("config.yml"))
    }

    @Test
    fun aFileWithOnlyFourPointZerosSettingsIsntZChatOne() {
        write("config.yml", "network:\n  enabled: true\n")
        val loaded = files().load()
        assertEquals(true, loaded.config[ZChatConfig.network.enabled])
        assertFalse(NioFiles.exists(dir.resolve("config.yml.old")))
    }

    @Test
    fun aBrokenGroupIsSkippedAndSaysWhy() {
        write("config.yml", "formatting:\n  groups:\n    vip:\n      weight: 10\n      format: \"<gold><player>: <message>\"\n    bad:\n      weight: lots\n")
        val loaded = files().load()
        assertEquals(setOf("vip"), loaded.config[ZChatConfig.formatting.groups].keys)
        assertEquals("zchat.group.vip", loaded.config[ZChatConfig.formatting.groups].getValue("vip").permission("vip"))
        assertTrue(loaded.problems.any { it.startsWith("formatting.groups.bad") }, loaded.problems.toString())
        assertEquals(Group(10, null, "<gold><player>: <message>"), loaded.config[ZChatConfig.formatting.groups]["vip"])
    }

    @Test
    fun filterEditsKeepTheOwnersFile() {
        write("config.yml", fixture("config-3.2.0.yml"))
        val files = files()
        files.load()
        val before = read("config.yml")
        assertEquals(Outcome.Ok(true), files.editWords("creeper*", add = true))
        assertEquals(Outcome.Ok(false), files.editWords("creeper*", add = true))
        assertEquals(listOf("badword1", "badword2", "creeper*"), files.load().config[ZChatConfig.filter.words])
        assertEquals(Outcome.Ok(true), files.editWords("creeper*", add = false))
        assertEquals(before, read("config.yml"), "comments and everything else kept")
    }

    @Test
    fun aConfigThatIsntYamlKeepsTheLastGoodOne() {
        val files = files()
        write("config.yml", "mutechat:\n  enabled: false\n")
        val first = files.load()
        write("config.yml", "mutechat: [\n")
        val second = files.load()
        assertEquals(first.config, second.config)
        assertTrue(second.problems.any { "keeping the settings from before" in it }, second.problems.toString())
    }
}
