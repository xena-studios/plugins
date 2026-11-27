package co.xenastudios.xmarket

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.Language
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.price.Currency
import kotlin.concurrent.Volatile

/**
 * xMarket's messages for each reader in their language, from the lang files (a reload builds
 * them again), and prices as each currency writes them: money as the economy formats it, an
 * item currency as `3 diamond`, levels as `5 levels`.
 */
public class Texts(private val languages: LanguageSource, private val server: () -> MarketServer?) {
    @Volatile
    private var loaded: Translations? = null

    /** The lang files as last loaded; English from the code until then. */
    public var translations: Translations
        get() = loaded ?: Translations.of(XMarketMessages.catalog, emptyMap()).also { loaded = it }
        set(value) {
            loaded = value
        }

    /** How money is written: the economy's own format, found by the plugin. */
    @Volatile
    public var money: (Money) -> String? = { null }

    public val admin: AdminText = AdminText { source, message, placeholders ->
        render(source.player, message, *placeholders.map { it.key to it.value }.toTypedArray())
    }

    public val feedback: CommandFeedback = CommandFeedback.of(admin)

    public fun language(player: PlayerRef?): Language = translations.languageFor(player?.let(languages::clientLocale))

    public fun render(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? {
        val language = language(player)
        return translations.render(message, language, fill(placeholders, language))
    }

    /** [message] as plain text with no markup left, for a menu line or the console. */
    public fun line(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component =
        render(player, message, *placeholders) ?: Component.EMPTY

    public fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(source.player, message, *placeholders)?.let(source::reply)
    }

    public fun send(player: PlayerRef, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        val s = server() ?: return
        render(player, message, *placeholders)?.let { s.send(player, it) }
    }

    /** [amount] of [currency] as [player] reads it. */
    public fun price(player: PlayerRef?, amount: Money, currency: Currency): String = when (currency) {
        is Currency.Money -> money(amount) ?: amount.toPlainString(if (amount.units % 100 == 0L) 2 else 4).let { trimmed(it) }
        is Currency.Xp -> line(player, XMarketMessages.currencyXp, "amount" to amount.whole).plain()
        is Currency.Items -> line(player, XMarketMessages.currencyItems, "amount" to amount.whole, "item" to itemName(currency.item.shortId)).plain()
    }

    // 2.5000 as 2.50, never fewer than two decimals.
    private fun trimmed(text: String): String {
        val dot = text.indexOf('.')
        if (dot < 0) return text
        var end = text.length
        while (end > dot + 3 && text[end - 1] == '0') end--
        return text.substring(0, end)
    }

    private fun fill(placeholders: Array<out Pair<String, Any>>, language: Language): Map<String, Placeholder> {
        if (placeholders.isEmpty()) return emptyMap()
        val filled = HashMap<String, Placeholder>(placeholders.size * 2)
        for ((name, value) in placeholders) {
            filled[name] = when (value) {
                is Placeholder -> value
                is Int -> Placeholder.text(language.formats.number(value))
                is Long -> Placeholder.text(language.formats.number(value))
                is kotlin.time.Duration -> Placeholder.text(language.formats.duration(value))
                is Component -> Placeholder.component(value)
                is MessageKey -> Placeholder.component(translations.render(value, language, emptyMap()) ?: Component.EMPTY)
                else -> Placeholder.text(value)
            }
        }
        return filled
    }

    public companion object {
        /** An item's id as a name to read, `red_wool` as `Red Wool`, for messages (menus show the client's own name). */
        public fun itemName(key: String): String {
            val id = key.substringAfter(':').substringBefore('{')
            val base = id.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            val inside = key.substringAfter('{', "").removeSuffix("}")
            if (inside.isEmpty()) return base
            val detail = inside.split(',').joinToString(", ") { part ->
                val (name, level) = if ('=' in part) part.split('=', limit = 2) else listOf(part, "")
                name.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } + if (level.isEmpty()) "" else " " + roman(level.toIntOrNull() ?: 1)
            }
            return "$base ($detail)"
        }

        private fun roman(n: Int): String = when (n) {
            1 -> "I"
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            else -> n.toString()
        }
    }
}
