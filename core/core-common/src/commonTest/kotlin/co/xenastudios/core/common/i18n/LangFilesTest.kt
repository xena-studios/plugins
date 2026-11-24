package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigError
import co.xenastudios.core.testing.TestYaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LangFilesTest {

    private val de: ShippedLanguage = assertIs<Outcome.Ok<ShippedLanguage>>(LangFiles.readShipped(DE, SHIPPED_DE, TestYaml)).value

    private fun load(tag: LanguageTag, shipped: ShippedLanguage?, text: String?): LangLoad =
        assertIs<Outcome.Ok<LangLoad>>(LangFiles.load(CATALOG, tag, shipped, text, TestYaml)).value

    @Test
    fun theEnglishFileIsWrittenInFullWithItsComments() {
        assertEquals(ENGLISH_FILE, LangFiles.defaultText(CATALOG, LanguageTag.ENGLISH, null))
    }

    @Test
    fun aShippedFileIsReadWithItsFormerTranslationsAndName() {
        assertEquals("<prefix>Zuhause <highlight><name></highlight> gesetzt.", de.messages["homes.set"])
        assertEquals(listOf("<prefix>Home <highlight><name></highlight> gesetzt."), de.former["homes.set"])
        assertEquals("Deutsch", de.name)
        assertTrue(de.machineDrafted)
        assertEquals(5, de.messages.size, "the translation section isn't a message")
    }

    @Test
    fun noFileWritesTheShippedLanguageInFull() {
        val load = load(DE, de, null)
        assertEquals(listOf(LangNotice.Kind.WROTE), load.notices.map { it.kind })
        val written = load.write!!
        assertTrue("# Messages in Deutsch (de)." in written, written)
        assertTrue("# Drafted by machine translation" in written, written)
        assertTrue("homes.set: \"<prefix>Zuhause <highlight><name></highlight> gesetzt.\"" in written, written)
        assertTrue("translation:" !in written, "the shipped file's own section isn't written: $written")
        assertEquals(de.messages, load.messages)
    }

    @Test
    fun theWrittenFileReadsBackUnchangedAndIsLeftAlone() {
        for ((tag, shipped) in listOf(LanguageTag.ENGLISH to null, DE to de)) {
            val written = LangFiles.defaultText(CATALOG, tag, shipped)
            val load = load(tag, shipped, written)
            assertNull(load.write, "$tag")
            assertEquals(emptyList(), load.problems, "$tag")
            assertEquals(emptyList(), load.notices, "$tag")
        }
    }

    @Test
    fun anEditedMessageIsKeptAndAnUntouchedOneFollowsTheUpdate() {
        val file = LangFiles.defaultText(CATALOG, DE, de)
            // Still on the translation the plugin shipped before: never edited.
            .replace("<prefix>Zuhause <highlight><name></highlight> gesetzt.", "<prefix>Home <highlight><name></highlight> gesetzt.")
            // The owner's own words.
            .replace("<error>Du hast kein Zuhause.", "<error>Noch kein Zuhause!")
        val load = load(DE, de, file)
        assertEquals("<prefix>Zuhause <highlight><name></highlight> gesetzt.", load.messages["homes.set"])
        assertEquals("<error>Noch kein Zuhause!", load.messages["homes.none"])
        assertEquals(listOf(LangNotice.Kind.UPDATED), load.notices.map { it.kind })
        assertEquals(listOf("homes.set"), load.notices[0].keys)
        val written = load.write!!
        assertTrue("homes.set: \"<prefix>Zuhause <highlight><name></highlight> gesetzt.\"" in written, written)
        assertTrue("homes.none: \"<error>Noch kein Zuhause!\"" in written, written)
    }

    @Test
    fun englishThatStoodInForAMissingTranslationGivesWayWhenItArrives() {
        // Written when the jar had no German for homes.list, so English stood in.
        val file = LangFiles.defaultText(CATALOG, DE, de)
            .replace("<prefix>Du hast <highlight><count></highlight> Zuhause.", HomesMessages.list.english)
        val load = load(DE, de, file)
        assertEquals("<prefix>Du hast <highlight><count></highlight> Zuhause.", load.messages["homes.list"])
        assertEquals(listOf("homes.list"), load.notices.single().keys)
    }

    @Test
    fun aFormerEnglishTextIsMovedToTheNewOne() {
        val file = LangFiles.defaultText(CATALOG, LanguageTag.ENGLISH, null)
            .replace(HomesMessages.set.english, "<prefix>Set home <name>.")
        val load = load(LanguageTag.ENGLISH, null, file)
        assertEquals(HomesMessages.set.english, load.messages["homes.set"])
        assertEquals(LangNotice.Kind.UPDATED, load.notices.single().kind)
    }

    @Test
    fun missingMessagesAreAddedAndUnknownKeysKept() {
        val file = """
            homes.set: "<prefix>Mein Zuhause <name>."
            homes.old: "Gone"
        """.trimIndent()
        val load = load(DE, de, file)
        assertEquals("<prefix>Mein Zuhause <name>.", load.messages["homes.set"])
        assertEquals(de.messages["homes.none"], load.messages["homes.none"])
        assertEquals(listOf(LangNotice.Kind.ADDED), load.notices.map { it.kind })
        assertEquals(listOf("homes.list", "homes.none", "teleport.go", "teleport.hidden"), load.notices[0].keys)
        assertEquals(listOf(LangProblem.Kind.UNKNOWN_KEY), load.problems.map { it.kind })
        assertEquals(2, load.problems[0].line)
        val written = load.write!!
        assertTrue(written.endsWith("\nhomes.old: \"Gone\"\n"), written)
        assertTrue("homes.set: \"<prefix>Mein Zuhause <name>.\"" in written, written)
    }

    @Test
    fun aMessageThatIsntTextIsReportedAndTheShippedOneUsed() {
        val file = LangFiles.defaultText(CATALOG, DE, de).replace(
            "homes.none: \"<error>Du hast kein Zuhause.\"",
            "homes.none:\n  - \"a\"\n  - \"b\"",
        )
        val load = load(DE, de, file)
        assertEquals(de.messages["homes.none"], load.messages["homes.none"])
        assertEquals(listOf(LangProblem.Kind.NOT_TEXT), load.problems.map { it.kind })
        assertNull(load.write, "the owner's value stays in the file")
    }

    @Test
    fun anEditUsingATagTheMessageDoesntFillIsReported() {
        val file = LangFiles.defaultText(CATALOG, LanguageTag.ENGLISH, null)
            .replace("<error>You have no homes.", "<error>No homes, <player>. <click:run_command:'/sethome'>Set one</click>")
        val load = load(LanguageTag.ENGLISH, null, file)
        assertEquals(listOf(LangProblem.Kind.FOREIGN_TAG), load.problems.map { it.kind }, "a click the owner adds is fine")
        assertTrue("<player>" in load.problems[0].message)
    }

    @Test
    fun aNestedSectionReadsLikeFlatKeysAndABlankMessageIsBlank() {
        val file = """
            homes:
              set: "<prefix>Set."
              list: "x <count>"
              none: "n"
            teleport:
              go: "g <name>"
              hidden:
        """.trimIndent()
        val load = load(LanguageTag.ENGLISH, null, file)
        assertEquals("<prefix>Set.", load.messages["homes.set"])
        assertEquals("", load.messages["teleport.hidden"])
        assertEquals(emptyList(), load.problems)
        assertNull(load.write)
    }

    @Test
    fun aLanguageTheOwnerAddedIsCompletedInEnglish() {
        val load = load(LanguageTag.of("it"), null, "homes.none: \"<error>Nessuna casa.\"\n")
        assertEquals("<error>Nessuna casa.", load.messages["homes.none"])
        assertEquals(HomesMessages.set.english, load.messages["homes.set"])
        assertTrue(load.write!!.startsWith("# Messages in it."), load.write)
    }

    @Test
    fun aFileThatIsntYamlIsAnError() {
        val error = assertIs<Outcome.Err<ConfigError>>(LangFiles.load(CATALOG, DE, de, "homes.set: \"unclosed\n", TestYaml)).error
        assertTrue(error.message.startsWith("lang/de.yml isn't valid YAML"), error.message)
        val list = assertIs<Outcome.Err<ConfigError>>(LangFiles.load(CATALOG, DE, de, "- a\n", TestYaml)).error
        assertTrue("not a single value or a list" in list.message, list.message)
    }

    private companion object {
        val ENGLISH_FILE = """
            # Messages in English (en). Edit any of them here: your edits are kept,
            # and a message you haven't edited gets the new text when an update changes it. Delete
            # this file to have it written again as shipped. <prefix>, <highlight>, <success> and
            # <error> are the theme's, set in config.yml; each message's own placeholders are listed
            # above it. A blank message ("") sends nothing.

            # When a player sets a home.
            # Placeholders: <name>
            homes.set: "<prefix>Home <highlight><name></highlight> set."
            # Placeholders: <count>
            homes.list: "<prefix>You have <highlight><count></highlight> homes."
            homes.none: "<error>You have no homes."

            # A line in /homes.
            # Placeholders: <name>
            teleport.go: "<click:run_command:'/home <name>'>Go to <name></click>"

            # Blank: sends nothing.
            teleport.hidden: ""
        """.trimIndent() + "\n"
    }
}
