package co.xenastudios.xutilities

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
import kotlin.time.Duration

/**
 * xUtilities' messages, drawn for each reader in their language from the lang files. Built
 * again on every reload ([translations]); a player's language is their client's, as the
 * family's player cache has it ([languages]); the console reads `default-language`.
 */
public class Texts(private val languages: LanguageSource) {
    /** The lang files as last loaded. */
    @Volatile
    public var translations: Translations = Translations.of(XUtilitiesMessages.catalog, emptyMap())

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
        val translations = translations
        val language = translations.languageFor(player?.let(languages::clientLocale))
        if (placeholders.isEmpty()) return translations.render(message, language)
        val filled = HashMap<String, Placeholder>(placeholders.size * 2)
        for ((name, value) in placeholders) {
            filled[name] = when (value) {
                is Placeholder -> value
                // A word from the lang files, in this reader's language: flight's <state>, a gamemode's name.
                is MessageKey -> Placeholder.text(translations.render(value, language)?.plain().orEmpty())
                is Int -> Placeholder.text(language.formats.number(value))
                is Long -> Placeholder.text(language.formats.number(value))
                is Duration -> Placeholder.text(language.formats.duration(value))
                is Component -> Placeholder.component(value)
                else -> Placeholder.text(value)
            }
        }
        return translations.render(message, language, filled)
    }

    /** [message] for whoever ran a command. */
    public fun render(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? =
        render(source.player, message, *placeholders)

    /** [message] as plain text for [player], for a menu's title. */
    public fun plain(player: PlayerRef?, message: MessageKey): String = render(player, message)?.plain().orEmpty()

    /** Replies [message] to [source], unless it's blank in their language. */
    public fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(source.player, message, *placeholders)?.let(source::reply)
    }
}
