package co.xenastudios.xvote

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.Language
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import kotlin.concurrent.Volatile

/**
 * xVote's messages, drawn for each reader in their language from the lang files, built again on
 * every reload ([translations]). A player's language is their client's; the console reads
 * `default-language`. Texts is its own [AdminText], for the admin subcommands' replies, and gives
 * the update checker the current [translations] (as a function, rather than a lambda of its own).
 */
public class Texts(private val languages: LanguageSource, internal val server: VoteServer?) : AdminText, () -> Translations {
    @Volatile
    private var loaded: Translations? = null

    @Volatile
    private var englishOnly: Translations? = null

    private val english: Translations
        get() = englishOnly ?: Translations.of(XVoteMessages.catalog, emptyMap()).also { englishOnly = it }

    /** The lang files as last loaded; English from the code until then. */
    public var translations: Translations
        get() = loaded ?: english
        set(value) {
            loaded = value
        }

    override fun invoke(): Translations = translations

    /** The admin subcommands' replies, from the same files. */
    public val admin: AdminText get() = this

    override fun render(source: CommandSource, message: MessageKey, placeholders: Map<String, Any>): Component? =
        render(source.player, message, *placeholders.map { it.key to it.value }.toTypedArray())

    /** What every command's sender is told when it doesn't run (`core.command.*`), from the same files. */
    public val feedback: CommandFeedback get() = CommandFeedback.of(this)

    /** [player]'s language: their client's, or `default-language` for the console. */
    public fun language(player: PlayerRef?): Language = translations.languageFor(player?.let(languages::clientLocale))

    /** [message] for [player] (the console for `null`), or `null` if it's blank in their language. */
    public fun render(player: PlayerRef?, message: MessageKey, vararg placeholders: Pair<String, Any>): Component? =
        render(language(player), message, placeholders.toList())

    public fun render(language: Language, message: MessageKey, placeholders: List<Pair<String, Any>>): Component? =
        translations.render(message, language, fill(placeholders, language))

    /** [message]'s words for [player], as plain text: a period's name. */
    public fun word(player: PlayerRef?, message: MessageKey): String = render(player, message)?.plain() ?: ""

    /** Replies [message] to [source], unless it's blank in their language. */
    public fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        render(source.player, message, *placeholders)?.let(source::reply)
    }

    /** Sends [message] to online [player], unless it's blank in their language. Any thread. */
    public fun send(player: PlayerRef, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        val server = server ?: return
        render(player, message, *placeholders)?.let { server.send(player, it) }
    }

    /** Sends [message] to everyone online and the console, drawn once per language. */
    public fun broadcast(message: MessageKey, vararg placeholders: Pair<String, Any>) {
        val server = server ?: return
        val drawn = HashMap<Int, Component?>()
        for (player in server.online()) {
            val language = language(player)
            val line = drawn.getOrPut(language.index) { render(language, message, placeholders.toList()) } ?: continue
            server.send(player, line)
        }
        render(null, message, *placeholders)?.let(server::console)
    }

    /**
     * Owner-written [markup] (a reward's message or broadcast) with the theme's tags and
     * [placeholders]; `null` if it's blank.
     */
    public fun owners(markup: String, placeholders: Map<String, Placeholder>): Component? {
        if (markup.isBlank()) return null
        val theme = translations.theme
        return theme.chat(Markup.parse(markup, theme.tags).render(placeholders))
    }

    private fun fill(placeholders: List<Pair<String, Any>>, language: Language): Map<String, Placeholder> {
        if (placeholders.isEmpty()) return emptyMap()
        val filled = HashMap<String, Placeholder>(placeholders.size * 2)
        for ((name, value) in placeholders) {
            if (name in filled) continue
            filled[name] = when (value) {
                is Placeholder -> value
                is Int -> Placeholder.text(language.formats.number(value))
                is Long -> Placeholder.text(language.formats.number(value))
                is Component -> Placeholder.component(value)
                is MessageKey -> Placeholder.component(translations.render(value, language, emptyMap()) ?: Component.EMPTY)
                is Word -> Placeholder.component(render(language, value.message, value.placeholders.toList()) ?: Component.EMPTY)
                else -> Placeholder.text(value)
            }
        }
        return filled
    }

    /** A message drawn inside another, in the same language, with its own placeholders. */
    public class Word(public val message: MessageKey, public vararg val placeholders: Pair<String, Any>)
}
