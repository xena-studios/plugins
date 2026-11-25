package co.xenastudios.zchat

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
 * zChat's messages, drawn for each reader in their language from the lang files. Built again on
 * every reload ([translations]); a player's language is their client's, as the family's player
 * cache has it ([languages]); the console reads `default-language`.
 */
public class Texts(private val languages: LanguageSource) {
    // English until the lang files are loaded, made on first use: not as the plugin is made on a
    // proxy's thread, where the loaded files usually come first.
    @Volatile
    private var loaded: Translations? = null
    private val english by lazy { Translations.of(ZChatMessages.catalog, emptyMap()) }

    /** The lang files as last loaded. */
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
    public fun render(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? =
        render(player, message, fill(placeholders, language(player)))

    private fun render(player: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>): Component? =
        translations.render(message, language(player), placeholders)

    /** [message] in [language], or `null` if it's blank there: for a line drawn once per language. */
    public fun render(language: Language, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? =
        translations.render(message, language, fill(placeholders, language))

    /** Replies [message] to [source], unless it's blank in their language. */
    public fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(source.player, message, *placeholders)?.let(source::reply)
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
                else -> Placeholder.text(value)
            }
        }
        return filled
    }
}
