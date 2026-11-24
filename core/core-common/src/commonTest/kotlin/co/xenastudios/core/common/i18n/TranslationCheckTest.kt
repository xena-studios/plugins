package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.TestYaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationCheckTest {

    private fun check(text: String, allowMissing: Boolean = false) = TranslationCheck.check(CATALOG, DE, text, TestYaml, allowMissing)

    @Test
    fun aCompleteTranslationPasses() {
        assertEquals(emptyList(), check(SHIPPED_DE))
        TranslationCheck.verify(CATALOG, mapOf(DE to SHIPPED_DE), TestYaml)
    }

    @Test
    fun missingKeysFailUnlessAPartialTranslationIsAllowed() {
        val issues = TranslationCheck.check(CATALOG, PT_BR, SHIPPED_PT_BR, TestYaml)
        assertEquals(listOf("homes.list", "teleport.go", "teleport.hidden"), issues.map { it.key })
        assertTrue(issues.all { it.kind == TranslationIssue.Kind.MISSING })
        assertEquals(emptyList(), TranslationCheck.check(CATALOG, PT_BR, SHIPPED_PT_BR, TestYaml, allowMissing = true))
    }

    @Test
    fun unknownKeysFailAlsoInFormer() {
        val text = SHIPPED_DE.replace("translation:", "homes.gone: \"x\"\ntranslation:") + "    homes.old: [\"y\"]\n"
        val issues = check(text)
        assertEquals(listOf(TranslationIssue.Kind.UNKNOWN_KEY, TranslationIssue.Kind.UNKNOWN_KEY), issues.map { it.kind })
        assertEquals(listOf("homes.gone", "homes.old"), issues.map { it.key })
    }

    @Test
    fun aTagTheEnglishDoesntFillFails() {
        val text = SHIPPED_DE.replace("<error>Du hast kein Zuhause.", "<error>Du hast kein Zuhause, <player>.")
        val issue = check(text).single()
        assertEquals(TranslationIssue.Kind.FOREIGN_TAG, issue.kind)
        assertEquals("homes.none", issue.key)
        assertTrue("<player>" in issue.message, issue.message)
    }

    @Test
    fun aClickOrHoverTheEnglishHasntFails() {
        val click = SHIPPED_DE.replace("<error>Du hast kein Zuhause.", "<click:run_command:'/op me'>Klick</click>")
        assertEquals(listOf("click"), check(click).map { it.message.substringAfter("<").substringBefore(">") })
        val hover = SHIPPED_DE.replace("<error>Du hast kein Zuhause.", "<hover:show_text:'<count>'>?</hover>")
        assertEquals(listOf(TranslationIssue.Kind.FOREIGN_TAG, TranslationIssue.Kind.FOREIGN_TAG), check(hover).map { it.kind }, "the hover, and <count> inside it")
    }

    @Test
    fun aPlaceholderInsideAClickCounts() {
        val text = SHIPPED_DE.replace("<click:run_command:'/home <name>'>Gehe zu <name></click>", "<click:run_command:'/home <id>'>Gehe zu <name></click>")
        assertEquals(listOf("<id>"), check(text).map { Regex("<[a-z]+>").find(it.message)!!.value })
    }

    @Test
    fun stylesAndTheThemeMayChangeFreely() {
        val text = SHIPPED_DE.replace("<error>Du hast kein Zuhause.", "<prefix><success><bold>Kein</bold> <#FF0000>Zuhause</#FF0000> <gradient:red:blue>hier</gradient>")
        assertEquals(emptyList(), check(text))
    }

    @Test
    fun aDroppedPlaceholderFails() {
        val text = SHIPPED_DE.replace("<prefix>Du hast <highlight><count></highlight> Zuhause.", "<prefix>Du hast Zuhause.")
        val issue = check(text).single()
        assertEquals(TranslationIssue.Kind.DROPPED_PLACEHOLDER, issue.kind)
        assertEquals("homes.list", issue.key)
    }

    @Test
    fun aFileThatIsntYamlFails() {
        assertEquals(TranslationIssue.Kind.INVALID_FILE, check("a: \"unclosed\n").single().kind)
    }

    @Test
    fun verifyListsEveryIssue() {
        val error = assertFailsWith<AssertionError> {
            TranslationCheck.verify(CATALOG, mapOf(DE to SHIPPED_DE, PT_BR to SHIPPED_PT_BR), TestYaml)
        }
        assertTrue(error.message!!.startsWith("3 translation issues:"), error.message)
    }

    @Test
    fun theCoreMessagesEnglishPassesItsOwnCheck() {
        val catalog = MessageCatalog.of(CoreMessages, HomesMessages)
        val english = catalog.messages.joinToString("\n") { "${it.key}: \"${it.english.replace("\"", "\\\"")}\"" }
        assertEquals(emptyList(), TranslationCheck.check(catalog, DE, english, TestYaml))
    }
}

class MessageBundleTest {

    private fun declare(block: TestBundle.() -> Unit): String? = runCatching { TestBundle().block() }.exceptionOrNull()?.message

    class TestBundle : MessageBundle() {
        fun add(key: String, english: String, vararg placeholders: String) = message(key, english, placeholders = placeholders.toList())
    }

    @Test
    fun aMessageMayUseStylesTheThemeAndItsPlaceholders() {
        assertNull(declare { add("a.b", "<prefix><highlight><b><player></b></highlight> <#123456>x <click:run_command:'/p <player>'>y</click>", "player") })
    }

    @Test
    fun aTagThatIsNeitherFailsAtDeclaration() {
        assertEquals("a uses <hilight>, which is neither a placeholder it declares nor a tag of the markup or the theme", declare { add("a", "<hilight>x") })
        assertEquals("a declares <player>, but its English text doesn't use it", declare { add("a", "x", "player") })
        assertEquals("a: <bold> is a tag of the markup or the theme, not a placeholder", declare { add("a", "<bold>x", "bold") })
    }

    @Test
    fun keysAreCheckedAndUnique() {
        assertTrue(declare { add("Homes.Set", "x") }!!.startsWith("message key \"Homes.Set\""))
        assertTrue(declare { add("a..b", "x") }!!.startsWith("message key \"a..b\""))
        assertEquals("message key translation.name: translation is the files' own", declare { add("translation.name", "x") })
        assertEquals("message a declared twice", declare { add("a", "x"); add("a", "y") })
    }

    @Test
    fun aCatalogRefusesAKeyInTwoBundles() {
        val one = TestBundle().apply { add("a", "x") }
        val two = TestBundle().apply { add("a", "y") }
        assertEquals("message a is in two bundles", runCatching { MessageCatalog.of(one, two) }.exceptionOrNull()?.message)
        val catalog = MessageCatalog.of(CoreMessages, HomesMessages)
        assertEquals(CoreMessages.messages.size, catalog.slot(HomesMessages.set))
        assertEquals(HomesMessages.set, catalog["homes.set"])
        assertEquals(-1, CATALOG.slot(CoreMessages.pagesFooter))
    }
}

class LanguageTagTest {

    @Test
    fun clientAndOwnerSpellingsReadTheSame() {
        assertEquals("pt-BR", LanguageTag.parse("pt_br").toString())
        assertEquals("pt-BR", LanguageTag.parse("PT-br").toString())
        assertEquals("zh-Hant-TW", LanguageTag.parse("zh_hant_tw").toString())
        assertEquals("es-419", LanguageTag.parse("es_419").toString())
        assertEquals("en", LanguageTag.parse("en_us_pirate")!!.language)
        assertEquals(LanguageTag.of("de"), LanguageTag.parse("DE"))
    }

    @Test
    fun nonsenseIsntATag() {
        for (bad in listOf("", "x", "1234", "en us", "ab_c!", "a".repeat(40))) assertNull(LanguageTag.parse(bad), bad)
    }

    @Test
    fun candidatesGoFromRegionToLanguage() {
        assertEquals(listOf("zh-Hant-TW", "zh-TW", "zh-Hant", "zh"), LanguageTag.of("zh-Hant-TW").candidates().map { it.toString() })
        assertEquals(listOf("pt-BR", "pt"), LanguageTag.of("pt_BR").candidates().map { it.toString() })
        assertEquals(listOf("de"), LanguageTag.of("de").candidates().map { it.toString() })
    }

    @Test
    fun theSettingsReadAutoOrATag() {
        assertEquals(LanguageChoice.Auto, assertIs<Outcome.Ok<LanguageChoice>>(LanguageChoice.TYPE.decode(scalar("AUTO"), ctx)).value)
        assertEquals(LanguageChoice.Fixed(LanguageTag.of("pt-BR")), assertIs<Outcome.Ok<LanguageChoice>>(LanguageChoice.TYPE.decode(scalar("pt_br"), ctx)).value)
        assertIs<Outcome.Err<String>>(LanguageChoice.TYPE.decode(scalar("automatic!"), ctx))
        assertEquals(LanguageTag.of("de"), assertIs<Outcome.Ok<LanguageTag>>(LanguageTag.TYPE.decode(scalar("de"), ctx)).value)
        assertEquals(co.xenastudios.core.common.config.YamlNode.Scalar("pt-BR"), LanguageTag.TYPE.encode(LanguageTag.of("pt_br")))
    }

    private val ctx = co.xenastudios.core.common.config.ConfigContext.DEFAULT
    private fun scalar(text: String) = co.xenastudios.core.common.config.YamlNode.Scalar(text)
}
