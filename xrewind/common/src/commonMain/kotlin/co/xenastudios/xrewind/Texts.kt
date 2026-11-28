package co.xenastudios.xrewind

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

/** xRewind's messages for each reader in their language, from the lang files (a reload builds them again). */
public class Texts(private val languages: LanguageSource, private val server: () -> RewindServer?) {
    @Volatile
    private var loaded: Translations? = null

    /** The lang files as last loaded; English from the code until then. */
    public var translations: Translations
        get() = loaded ?: Translations.of(XRewindMessages.catalog, emptyMap()).also { loaded = it }
        set(value) {
            loaded = value
        }

    public val admin: AdminText = AdminText { source, message, placeholders ->
        render(source.player, message, *placeholders.map { it.key to it.value }.toTypedArray())
    }

    public val feedback: CommandFeedback = CommandFeedback.of(admin)

    public fun language(player: PlayerRef?): Language = translations.languageFor(player?.let(languages::clientLocale))

    public fun render(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? {
        val language = language(player)
        return translations.render(message, language, fill(placeholders, language))
    }

    /** [message] as plain text, for a word inside another message. */
    public fun word(player: PlayerRef?, message: MessageKey): String = render(player, message)?.plain() ?: message.english

    public fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(source.player, message, *placeholders)?.let(source::reply)
    }

    public fun send(player: PlayerRef, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        val s = server() ?: return
        render(player, message, *placeholders)?.let { s.send(player, it) }
    }

    /** A number as [player] reads it: `1,234` or `1.234`. */
    public fun number(player: PlayerRef?, value: Long): String = language(player).formats.number(value)

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
                else -> Placeholder.text(value.toString())
            }
        }
        return filled
    }
}
