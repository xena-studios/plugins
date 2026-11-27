package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.Language
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Component
import kotlin.concurrent.Volatile

/**
 * xBans' messages, drawn for each reader in their language from the lang files, built again on
 * every reload ([translations]). A player's language is their client's, as the family's player
 * cache has it, also for a player who isn't online yet: a ban screen is drawn in the language
 * they last played in. The console reads `default-language`.
 */
public class Texts(private val languages: LanguageSource, internal val server: BansServer?) : AdminText {
    @Volatile
    private var loaded: Translations? = null

    @Volatile
    private var englishOnly: Translations? = null

    private val english: Translations
        get() = englishOnly ?: Translations.of(XBansMessages.catalog, emptyMap()).also { englishOnly = it }

    /** The lang files as last loaded; English from the code until then. */
    public var translations: Translations
        get() = loaded ?: english
        set(value) {
            loaded = value
        }

    /** `lists.date-format`, and `screens.appeal-link`, from the config in use. */
    // What a punishment's placeholders need from the config: `lists.date-format`, read on first
    // use, and the appeal link. Fields, not a class of their own.
    @Volatile
    internal var datePattern: String = "yyyy-MM-dd HH:mm"

    @Volatile
    internal var dates: Dates? = null

    @Volatile
    internal var appeal: String = "https://example.com/appeal"

    /** Uses the config's date format and appeal link from now on. */
    public fun look(datePattern: String, appeal: String) {
        if (datePattern != this.datePattern) dates = null
        this.datePattern = datePattern
        this.appeal = appeal
    }

    /** The admin subcommands' replies, from the same files. */
    public val admin: AdminText get() = this

    // Texts is its own AdminText, rather than a lambda: one class fewer at startup.
    override fun render(source: CommandSource, message: MessageKey, placeholders: Map<String, Any>): Component? =
        render(source.player, message, *placeholders.map { it.key to it.value }.toTypedArray())

    /** What every command's sender is told when it doesn't run (`core.command.*`), from the same files. */
    public val feedback: CommandFeedback = CommandFeedback.of(admin)

    /** [player]'s language: their client's, or `default-language` for the console. */
    public fun language(player: PlayerRef?): Language = translations.languageFor(player?.let(languages::clientLocale))

    /** A message drawn inside another, in the same language: `<type>`, `<status>`, the alts' states. */
    public class Word(public val message: MessageKey, public vararg val placeholders: Pair<String, Any>)
}
