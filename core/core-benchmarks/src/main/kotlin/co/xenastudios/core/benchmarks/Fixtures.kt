package co.xenastudios.core.benchmarks

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations

/** A plugin's messages, as a real one writes them: the theme's tags, a placeholder, a click. */
internal object BenchMessages : MessageBundle() {
    val balance: MessageKey = message("coins.balance", "<prefix>You have <highlight><amount></highlight> coins.", placeholders = listOf("amount"))
    val noPermission: MessageKey = message("coins.no-permission", "<error>You can't do that.")
    val paid: MessageKey = message(
        "coins.paid",
        "<prefix>Paid <highlight><amount></highlight> to <click:suggest_command:'/pay <player> '><hover:show_text:'<gray>Pay again'><player></hover></click>.",
        placeholders = listOf("amount", "player"),
    )
}

internal val CATALOG: MessageCatalog = MessageCatalog.of(BenchMessages)

/** English from the code, and a German file, as Translations.of takes them. */
internal fun translations(): Translations = Translations.of(
    CATALOG,
    mapOf(
        LanguageTag.of("de") to mapOf(
            "coins.balance" to "<prefix>Du hast <highlight><amount></highlight> Münzen.",
            "coins.no-permission" to "<error>Das darfst du nicht.",
            "coins.paid" to "<prefix><highlight><amount></highlight> an <click:suggest_command:'/pay <player> '><hover:show_text:'<gray>Nochmal'><player></hover></click> gezahlt.",
        ),
    ),
)

/** Every player reads in the language the benchmark is for. */
internal class FixedLanguage(private val locale: String) : LanguageSource {
    override fun clientLocale(player: PlayerRef): String = locale
}
