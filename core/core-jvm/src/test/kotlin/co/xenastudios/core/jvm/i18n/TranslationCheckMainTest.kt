package co.xenastudios.core.jvm.i18n

import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.TranslationIssue
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A shop plugin's reference, as `checkTranslations` loads it by name. */
object ShopReference : ManifestSource {
    private val tree: CommandTree = commands { root("shop", "Opens the shop.") { executes { } } }
    override val reference: PluginReference = PluginReference("xshop", "xShop", "A shop.", tree, emptyList(), catalog = MessageCatalog.of(ShopMessages))
}

/** A plugin that sends no messages. */
object SilentReference : ManifestSource {
    override val reference: PluginReference =
        PluginReference("xquiet", "xQuiet", "Says nothing.", commands { root("quiet", "Nothing.") { executes { } } }, emptyList())
}

class TranslationCheckMainTest {
    private val dir = Files.createTempDirectory("lang-check")

    private fun write(name: String, text: String) {
        Files.write(dir.resolve(name), text.toByteArray())
    }

    private fun check(vararg extra: String) = check(listOf("--source", ShopReference::class.java.name, "--lang", dir.toString()) + extra)!!

    @Test
    fun aCompleteTranslationPasses() {
        write("de.yml", "shop.bought: \"<prefix>Du hast <highlight><item></highlight> für <price> gekauft.\"\nshop.empty: \"<error>Leer.\"\n")
        assertEquals(emptyList(), check())
    }

    @Test
    fun missingKeysAndBrokenTagsFail() {
        write("de.yml", "shop.bought: \"<prefix>Du hast <item> für <prise> gekauft.\"\n")
        write("pt_BR.yml", "shop.empty: \"Vazio\"\n")
        val issues = check()
        assertEquals(
            listOf(TranslationIssue.Kind.FOREIGN_TAG, TranslationIssue.Kind.DROPPED_PLACEHOLDER, TranslationIssue.Kind.MISSING, TranslationIssue.Kind.INVALID_FILE),
            issues.map { it.kind },
            issues.joinToString("\n"),
        )
        assertEquals("lang/pt_BR.yml: the name should be a language tag, such as de.yml or pt-BR.yml", issues.last().message)
        assertEquals(listOf(TranslationIssue.Kind.FOREIGN_TAG, TranslationIssue.Kind.DROPPED_PLACEHOLDER, TranslationIssue.Kind.INVALID_FILE), check("--allow-missing").map { it.kind })
    }

    @Test
    fun aPluginWithoutMessagesHasNothingToCheck() {
        assertNull(check(listOf("--source", SilentReference::class.java.name, "--lang", dir.toString())))
    }
}
