package co.xenastudios.xbackup

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.Language
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Placeholder
import kotlin.concurrent.Volatile

/**
 * xBackup's messages for each reader in their language, from the lang files (a reload builds them
 * again). Text from elsewhere (a reason, a backup's reason, a path) goes in as text, never markup.
 */
public class Texts(private val languages: LanguageSource) {
    @Volatile
    private var loaded: Translations? = null

    /** The lang files as last loaded; English from the code until then. */
    public var translations: Translations
        get() = loaded ?: Translations.of(XBackupMessages.catalog, emptyMap()).also { loaded = it }
        set(value) {
            loaded = value
        }

    public val admin: AdminText = AdminText { source, message, placeholders ->
        render(source.player, message, *placeholders.map { it.key to it.value }.toTypedArray())
    }

    public fun language(player: PlayerRef?): Language = translations.languageFor(player?.let(languages::clientLocale))

    public fun render(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? {
        val language = language(player)
        return translations.render(message, language, fill(placeholders, language))
    }

    /** [message] for [player], or nothing: for parts of another message. */
    public fun part(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component =
        render(player, message, *placeholders) ?: Component.EMPTY

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
                is MessageKey -> Placeholder.component(translations.render(value, language, emptyMap()) ?: Component.EMPTY)
                else -> Placeholder.text(value)
            }
        }
        return filled
    }
}
