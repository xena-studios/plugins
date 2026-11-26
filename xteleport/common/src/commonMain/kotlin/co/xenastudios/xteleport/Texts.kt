package co.xenastudios.xteleport

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
 * xTeleport's messages, drawn for each reader in their language from the lang files, built again
 * on every reload ([translations]). A player's language is their client's, as the family's player
 * cache has it; the console reads `default-language`. [send] reaches players through the
 * adapter's [TeleportServer].
 */
public class Texts(private val languages: LanguageSource, private val server: () -> TeleportServer?) {
    @Volatile
    private var loaded: Translations? = null

    @Volatile
    private var englishOnly: Translations? = null

    private val english: Translations
        get() = englishOnly ?: Translations.of(XTeleportMessages.catalog, emptyMap()).also { englishOnly = it }

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
        return translations.render(message, language, fill(placeholders, language))
    }

    /** Replies [message] to [source], unless it's blank in their language. */
    public fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(source.player, message, *placeholders)?.let(source::reply)
    }

    /** Sends [message] to online [player], unless it's blank in their language. Any thread. */
    public fun send(player: PlayerRef, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        val server = server() ?: return
        render(player, message, *placeholders)?.let { server.send(player, it) }
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
}
