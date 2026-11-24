package co.xenastudios.core.common.i18n

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.TextRenderer
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Where a player's language comes from, fed by the platform: the client's locale, as it sends
 * it (Paper's `Player.locale()`, Spigot's `Player.getLocale()`, Fabric's client options), and
 * a call when it changes (`PlayerLocaleChangeEvent`). The family's
 * [co.xenastudios.core.common.family.PlayerCache] is one: the platform records the locale there
 * when a player joins and when it changes, and every plugin reads it.
 *
 * Players are [PlayerRef]s, as everywhere else in the core; the console has no reference, and
 * reads in `default-language`.
 */
public interface LanguageSource {
    /**
     * The locale [player]'s client sent (`pt_br`), or `null` for a client that hasn't sent one
     * yet (before it finishes logging in). Called on every send: keep it a field read.
     */
    public fun clientLocale(player: PlayerRef): String?

    /**
     * Calls [listener] whenever a player's client changes language, for text drawn once per
     * player and kept (a scoreboard, a boss bar), which should be drawn again. A platform that
     * can't tell never calls it; messages need nothing, since each send reads [clientLocale].
     */
    public fun onLanguageChange(listener: LanguageChangeListener) {}
}

/** Told when a player's client changes language: see [LanguageSource.onLanguageChange]. */
public fun interface LanguageChangeListener {
    public fun languageChanged(player: PlayerRef)
}

/** A broadcast's placeholders, made once per language: numbers and dates formatted for it. */
public fun interface LanguagePlaceholders {
    public fun placeholders(language: Language): Map<String, Placeholder>
}

/**
 * Sends drawn text to one reader: the player [viewer] names (`Bukkit.getPlayer(uuid)`), or the
 * console when it's `null`.
 */
public fun interface Delivery<in T> {
    public fun deliver(viewer: PlayerRef?, text: T)
}

/**
 * Draws messages to the server's own text ([T], by its [renderer]) for each reader's
 * language. A reader is a player's [PlayerRef], or `null` for the console, which reads in
 * `default-language`. What every send goes through:
 * - a message with no placeholders is drawn once per language and kept, so a send is two
 *   array reads;
 * - one with placeholders is rendered and drawn per send (or per language, for a broadcast);
 * - a blank message draws to `null`: send nothing.
 *
 * Built with each [Translations], so a reload starts with nothing kept. Safe to share
 * between threads: two threads may draw the same message at once, and either result is kept.
 */
public class MessageDrawer<T : Any>(
    public val translations: Translations,
    private val source: LanguageSource,
    private val renderer: TextRenderer<T>,
) {
    // Drawn texts, by language then message slot; BLANK marks a message that sends nothing.
    // Atomic, so a platform's mutable text (a BaseComponent[]) drawn on one thread is seen
    // whole on another.
    @OptIn(ExperimentalAtomicApi::class)
    private val drawn: Array<AtomicArray<Any?>> = Array(translations.languages.size) { AtomicArray<Any?>(translations.catalog.size) { null } }

    /**
     * [viewer]'s language: see [Translations.languageFor]. A player whose client hasn't said
     * gets `default-language`, as the console does.
     */
    public fun languageOf(viewer: PlayerRef?): Language = translations.languageFor(viewer?.let(source::clientLocale))

    /** [message] in [language], drawn once and kept; `null` if it's blank there. */
    @OptIn(ExperimentalAtomicApi::class)
    public fun draw(message: MessageKey, language: Language): T? {
        val slot = translations.slot(message)
        val row = drawn[language.index]
        val kept = row.loadAt(slot)
        if (kept != null) {
            @Suppress("UNCHECKED_CAST")
            return if (kept === BLANK) null else kept as T
        }
        val text = translations.render(message, language)?.let { renderer.draw(it) }
        row.storeAt(slot, text ?: BLANK)
        return text
    }

    /** [message] in [language] with [placeholders]; `null` if it's blank there. */
    public fun draw(message: MessageKey, language: Language, placeholders: Map<String, Placeholder>): T? {
        if (placeholders.isEmpty()) return draw(message, language)
        return translations.render(message, language, placeholders)?.let { renderer.draw(it) }
    }

    /** [message] in [viewer]'s language, with [placeholders]; `null` if it's blank there. */
    public fun draw(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder> = emptyMap()): T? =
        draw(message, languageOf(viewer), placeholders)

    /** Draws [message] for [viewer] and hands it to [delivery], unless it's blank. Returns whether it was sent. */
    public fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>, delivery: Delivery<T>): Boolean {
        val text = draw(viewer, message, placeholders) ?: return false
        delivery.deliver(viewer, text)
        return true
    }

    /**
     * [viewers] by language, in the order each language is first met. With `language: auto`
     * a group per language in use; with a fixed one, at most two (players, and the console in
     * `default-language`).
     */
    public fun byLanguage(viewers: Iterable<PlayerRef?>): Map<Language, List<PlayerRef?>> {
        val groups = LinkedHashMap<Language, MutableList<PlayerRef?>>()
        for (viewer in viewers) groups.getOrPut(languageOf(viewer)) { ArrayList() } += viewer
        return groups
    }

    /**
     * Sends [message] to every one of [viewers] (the console among them, if it should see
     * it), drawn once per language rather than once per viewer: [placeholders] is asked once
     * per language, so its numbers and dates suit each. Returns how many times it drew, which
     * is at most the number of languages in use.
     */
    public fun broadcast(viewers: Iterable<PlayerRef?>, message: MessageKey, placeholders: LanguagePlaceholders?, delivery: Delivery<T>): Int {
        var draws = 0
        for ((language, group) in byLanguage(viewers)) {
            val values = placeholders?.placeholders(language).orEmpty()
            val text = draw(message, language, values)
            draws++
            if (text != null) for (viewer in group) delivery.deliver(viewer, text)
        }
        return draws
    }

    /** [broadcast] with the same placeholders for every language (names, not numbers). */
    public fun broadcast(viewers: Iterable<PlayerRef?>, message: MessageKey, placeholders: Map<String, Placeholder>, delivery: Delivery<T>): Int =
        broadcast(viewers, message, if (placeholders.isEmpty()) null else LanguagePlaceholders { placeholders }, delivery)

    private object BLANK
}
