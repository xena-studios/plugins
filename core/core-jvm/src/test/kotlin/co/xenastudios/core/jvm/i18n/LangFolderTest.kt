package co.xenastudios.core.jvm.i18n

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.LangNotice
import co.xenastudios.core.common.i18n.LangProblem
import co.xenastudios.core.common.i18n.LanguageSettings
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.TranslationCheck
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.jvm.config.JvmYaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object ShopMessages : MessageBundle() {
    val bought: MessageKey = message(
        "shop.bought", "<prefix>You bought <highlight><item></highlight> for <highlight><price></highlight>.",
        "After a purchase.",
        placeholders = listOf("item", "price"),
    )
    val empty: MessageKey = message("shop.empty", "<error>The shop is empty.")
}

class LangFolderTest {

    private val dir: Path = Files.createTempDirectory("lang-folder")
    private val catalog = MessageCatalog.of(CoreMessages, ShopMessages)
    private val de = LanguageTag.of("de")
    private val shipped = LangFolder.shipped(javaClass.classLoader, listOf(LanguageTag.ENGLISH, de, LanguageTag.of("fr")))
    private val folder = LangFolder(dir, catalog, shipped)

    @AfterTest
    fun cleanUp() {
        val lang = dir.resolve("lang")
        if (Files.isDirectory(lang)) Files.setPosixFilePermissions(lang, PosixFilePermissions.fromString("rwxr-xr-x"))
        dir.toFile().deleteRecursively()
    }

    private fun read(name: String) = String(Files.readAllBytes(dir.resolve("lang/$name")), Charsets.UTF_8)

    @Test
    fun theJarsTranslationsPassTheCheck() {
        TranslationCheck.verify(catalog, shipped, JvmYaml)
    }

    @Test
    fun theFirstStartWritesEveryShippedLanguageInFull() {
        assertEquals(setOf(de), shipped.keys, "English is code, and the jar has no French")
        val loaded = folder.load()
        assertEquals(emptyList(), loaded.problems)
        assertEquals(listOf(LangNotice.Kind.WROTE, LangNotice.Kind.WROTE), loaded.notices.map { it.kind })
        assertTrue("shop.empty: \"<error>The shop is empty.\"" in read("en.yml"))
        assertTrue("shop.empty: \"<error>Der Laden ist leer.\"" in read("de.yml"))
        val t = loaded.translations
        val text = t.render(ShopMessages.bought, t.languageFor("de_de"), "item" to Placeholder.text("Brot"), "price" to Placeholder.text(t.languageFor("de_de").formats.number(1234.5)))!!
        assertEquals("» Du hast Brot für 1.234,5 gekauft.", text.plain())
    }

    @Test
    fun aSecondStartWritesNothingAndKeepsEdits() {
        folder.load()
        Files.write(dir.resolve("lang/de.yml"), read("de.yml").replace("Der Laden ist leer.", "Alles ausverkauft!").toByteArray())
        val before = Files.getLastModifiedTime(dir.resolve("lang/en.yml"))
        val loaded = folder.load()
        assertEquals(emptyList(), loaded.notices)
        assertEquals(before, Files.getLastModifiedTime(dir.resolve("lang/en.yml")))
        assertEquals("<error>Alles ausverkauft!", loaded.translations.text(ShopMessages.empty, loaded.translations.language(de)!!))
    }

    @Test
    fun anUntouchedFormerTranslationFollowsTheUpdate() {
        folder.load()
        Files.write(dir.resolve("lang/de.yml"), read("de.yml").replace("<error>Der Laden ist leer.", "<error>Laden leer.").toByteArray())
        val loaded = folder.load()
        assertEquals(listOf(LangNotice.Kind.UPDATED), loaded.notices.map { it.kind })
        assertTrue("<error>Der Laden ist leer." in read("de.yml"))
    }

    @Test
    fun aDeletedFileIsWrittenAgain() {
        folder.load()
        Files.delete(dir.resolve("lang/de.yml"))
        val loaded = folder.load()
        assertEquals(listOf(LangNotice.Kind.WROTE), loaded.notices.map { it.kind })
        assertTrue(Files.exists(dir.resolve("lang/de.yml")))
    }

    @Test
    fun aBrokenFileIsLeftAloneAndTheShippedTextUsed() {
        folder.load()
        val broken = "shop.empty: \"unclosed\nshop.bought: [\n"
        Files.write(dir.resolve("lang/de.yml"), broken.toByteArray())
        val loaded = folder.load()
        assertEquals(listOf(LangProblem.Kind.UNREADABLE), loaded.problems.map { it.kind })
        assertTrue(loaded.problems[0].message.startsWith("lang/de.yml isn't valid YAML"), loaded.problems[0].message)
        assertEquals(broken, read("de.yml"))
        assertEquals("<error>Der Laden ist leer.", loaded.translations.text(ShopMessages.empty, loaded.translations.language(de)!!))
    }

    @Test
    fun aLanguageTheOwnerAddsIsUsedAndAMisnamedOneReported() {
        Files.createDirectories(dir.resolve("lang"))
        Files.write(dir.resolve("lang/it.yml"), "shop.empty: \"<error>Negozio vuoto.\"\n".toByteArray())
        Files.write(dir.resolve("lang/pt_br.yml"), "shop.empty: \"x\"\n".toByteArray())
        val loaded = folder.load(LanguageSettings(default = LanguageTag.of("it")))
        val t = loaded.translations
        assertEquals(LanguageTag.of("it"), t.languageFor(null).tag)
        assertEquals("<error>Negozio vuoto.", t.text(ShopMessages.empty, t.default))
        assertEquals(listOf(LangProblem.Kind.UNREADABLE), loaded.problems.map { it.kind })
        assertTrue("name it pt-BR.yml" in loaded.problems[0].message, loaded.problems[0].message)
        assertTrue("shop.bought: \"<prefix>You bought" in read("it.yml"), "completed in English")
        assertFalse(Files.exists(dir.resolve("lang/pt-BR.yml")))
    }

    @Test
    fun aFolderThatCantBeWrittenStillLoads() {
        val lang = Files.createDirectories(dir.resolve("lang"))
        Files.setPosixFilePermissions(lang, PosixFilePermissions.fromString("r-xr-xr-x"))
        // Root writes anyway (some CI containers): nothing to test there.
        if (Files.isWritable(lang)) return
        val loaded = folder.load()
        assertEquals(listOf(LangProblem.Kind.SAVE_FAILED, LangProblem.Kind.SAVE_FAILED), loaded.problems.map { it.kind })
        assertEquals("<error>Der Laden ist leer.", loaded.translations.text(ShopMessages.empty, loaded.translations.language(de)!!))
    }
}
