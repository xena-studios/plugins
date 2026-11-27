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
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.model.Length
import kotlin.concurrent.Volatile

/**
 * xBans' messages, drawn for each reader in their language from the lang files, built again on
 * every reload ([translations]). A player's language is their client's, as the family's player
 * cache has it, also for a player who isn't online yet: a ban screen is drawn in the language
 * they last played in. The console reads `default-language`.
 */
public class Texts(private val languages: LanguageSource, private val server: () -> BansServer?) {
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
    @Volatile
    public var look: Look = Look(Dates.of("yyyy-MM-dd HH:mm"), "https://example.com/appeal")

    /** What a punishment's placeholders need from the config. */
    public class Look(public val dates: Dates, public val appeal: String)

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
        render(language(player), message, placeholders.toList())

    /** [message] in [language], with [placeholders]. */
    public fun render(language: Language, message: MessageKey, placeholders: List<Pair<String, Any>>): Component? =
        translations.render(message, language, fill(placeholders, language))

    /** [message] about [entry] (every placeholder a punishment has, then [more]) for [player]. */
    public fun about(player: PlayerRef?, message: MessageKey, entry: Entry, now: Long, vararg more: Pair<String, Any>): Component? =
        about(language(player), message, entry, now, *more)

    public fun about(language: Language, message: MessageKey, entry: Entry, now: Long, vararg more: Pair<String, Any>): Component? =
        render(language, message, more.toList() + of(entry, now))

    /**
     * [message] in [language] with [before] put in front of it (after its `<prefix>`, if it starts
     * with one): a silent punishment's broadcast, `» [Silent] Console banned Steve`. `null` if the
     * message is blank there.
     */
    public fun prefixed(language: Language, message: MessageKey, before: MessageKey, placeholders: List<Pair<String, Any>>): Component? {
        val t = translations
        val text = t.text(message, language)
        if (text.isBlank()) return null
        val extra = t.text(before, language)
        val combined = if (text.startsWith(PREFIX)) PREFIX + extra + text.substring(PREFIX.length) else extra + text
        val markup = co.xenastudios.core.common.text.Markup.parse(combined, t.theme.tags)
        return t.theme.chat(markup.render(fill(placeholders, language)))
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

    /** A date as `lists.date-format` writes it, in the server's time zone. */
    public fun date(epochMillis: Long): String = look.dates.format(epochMillis, server()?.utcOffsetSeconds(epochMillis) ?: 0)

    /**
     * Every placeholder a punishment fills: for screens, broadcasts and list lines. `<type>`,
     * `<status>` and `<duration>` are words from the lang files, drawn in the reader's language.
     */
    public fun of(entry: Entry, now: Long): List<Pair<String, Any>> {
        val p = entry.punishment
        val left = entry.left(now)
        val created = p.created.toEpochMilliseconds()
        val expires = p.expires?.toEpochMilliseconds()
        val length = if (expires == null) "permanent" else Length.format(expires - created)
        return listOf(
            "id" to p.id.toString(),
            "player" to p.targetName,
            "staff" to p.staffName,
            "reason" to p.reason,
            "scope" to if (p.isGlobal) "global" else p.scope,
            "date" to date(created),
            "expires" to if (expires == null) "never" else date(expires),
            "time-left" to if (left < 0) "forever" else Length.format(left),
            "length" to length,
            "duration" to if (expires == null) Word(XBansMessages.permanently) else Word(XBansMessages.forTime, "time" to Length.format(expires - created)),
            "appeal" to look.appeal,
            "type" to Word(typeWord(entry)),
            "status" to status(entry, now),
        )
    }

    private fun typeWord(entry: Entry): MessageKey = when (entry.type) {
        PunishmentType.BAN -> if (entry.punishment.ipBased) XBansMessages.typeIpBan else XBansMessages.typeBan
        PunishmentType.MUTE -> if (entry.punishment.ipBased) XBansMessages.typeIpMute else XBansMessages.typeMute
        PunishmentType.WARN -> XBansMessages.typeWarn
        PunishmentType.KICK -> XBansMessages.typeKick
    }

    private fun status(entry: Entry, now: Long): Word {
        val p = entry.punishment
        if (!p.type.lasts) return Word(XBansMessages.statusKick)
        p.removal?.let { return Word(XBansMessages.statusRevoked, "staff" to it.staffName) }
        if (entry.active(now)) {
            val left = entry.left(now)
            return Word(XBansMessages.statusActive, "time" to if (left < 0) "permanent" else Length.format(left))
        }
        return Word(XBansMessages.statusEnded)
    }

    private companion object {
        const val PREFIX = "<prefix>"
    }

    /** A message drawn inside another, in the same language: `<type>`, `<status>`, the alts' states. */
    public class Word(public val message: MessageKey, public vararg val placeholders: Pair<String, Any>)

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
                is Word -> Placeholder.component(render(language, value.message, value.placeholders.toList()) ?: Component.EMPTY)
                is MessageKey -> Placeholder.component(translations.render(value, language, emptyMap()) ?: Component.EMPTY)
                else -> Placeholder.text(value)
            }
        }
        return filled
    }
}
