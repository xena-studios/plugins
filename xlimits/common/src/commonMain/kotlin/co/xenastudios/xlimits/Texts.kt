package co.xenastudios.xlimits

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
import kotlin.concurrent.Volatile

/**
 * xLimits' messages, drawn for each reader in their language from the lang files, built again on
 * every reload ([translations]). A player's language is their client's, as the family's player
 * cache has it; the console reads `default-language`.
 */
public class Texts(private val languages: LanguageSource, private val server: LimitsServer) {
    @Volatile
    private var loaded: Translations? = null

    @Volatile
    private var englishOnly: Translations? = null

    private val english: Translations
        get() = englishOnly ?: Translations.of(XLimitsMessages.catalog, emptyMap()).also { englishOnly = it }

    /** The lang files as last loaded; English from the code until then. */
    public var translations: Translations
        get() = loaded ?: english
        set(value) {
            loaded = value
        }

    /** The admin subcommands' replies, from the same files. */
    public val admin: AdminText = AdminText { source, message, placeholders ->
        render(source.player, message, *placeholders.map { it.key to it.value }.toTypedArray())
    }

    /** What every command's sender is told when it doesn't run (`core.command.*`), from the same files. */
    public val feedback: CommandFeedback = CommandFeedback.of(admin)

    /** [player]'s language: their client's, or `default-language` for the console. */
    public fun language(player: PlayerRef?): Language = translations.languageFor(player?.let(languages::clientLocale))

    /** [message] for [player] (the console for `null`), or `null` if it's blank in their language. */
    public fun render(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? {
        val language = language(player)
        val filled = if (placeholders.isEmpty()) emptyMap() else placeholders.associate { (name, value) ->
            name to when (value) {
                is Placeholder -> value
                // Coordinates go as text, never formatted: they're also in click commands.
                is Int -> Placeholder.text(language.formats.number(value))
                is Double -> Placeholder.text(language.formats.number(value, 1))
                is kotlin.time.Duration -> Placeholder.text(language.formats.duration(value))
                is Component -> Placeholder.component(value)
                else -> Placeholder.text(value)
            }
        }
        return translations.render(message, language, filled)
    }

    /** Replies [message] to [source], unless it's blank in their language. */
    public fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(source.player, message, *placeholders)?.let(source::reply)
    }

    /** Sends [message] to online [player], unless it's blank in their language. Any thread. */
    public fun send(player: PlayerRef, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(player, message, *placeholders)?.let { server.send(player, it) }
    }
}
