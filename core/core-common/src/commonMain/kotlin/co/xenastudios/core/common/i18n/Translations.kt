package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.Theme
import kotlin.concurrent.Volatile

/**
 * One language messages are drawn in: its [tag], its number and date [formats], and the text
 * of every message, each parsed the first time it's drawn and then kept.
 */
public class Language internal constructor(
    public val tag: LanguageTag,
    /** Where it is in [Translations.languages]. */
    public val index: Int,
    private val texts: Array<String>,
) {
    /** Numbers, lengths of time and dates for this language. */
    public val formats: Formats = Formats.of(tag)

    // Each message parsed once, on first use: a racing thread may parse it too; either is fine to keep.
    private val parsed = arrayOfNulls<Parsed>(texts.size)

    internal class Parsed(val markup: Markup, val blank: Boolean) {
        // The message as sent in chat with no placeholders, kept: most messages have none.
        @Volatile
        var chat: Component? = null
    }

    internal fun parsed(slot: Int, theme: Theme): Parsed = parsed[slot] ?: run {
        val text = texts[slot]
        Parsed(Markup.parse(text, theme.tags), text.isBlank()).also { parsed[slot] = it }
    }

    /** The text of the message in [slot], as its file has it. */
    internal fun text(slot: Int): String = texts[slot]

    override fun toString(): String = tag.toString()
}

/**
 * Every message of a [MessageCatalog] in every language the plugin has, with the theme and
 * the language settings: what draws a message for a reader. Built on load and on reload, then
 * read from any thread; a new theme or new files mean a new one.
 *
 * - Each message is parsed once per language that uses it, with the theme's tags, so the
 *   theme applies to every language; placeholders are filled in per send.
 * - A reader's language is matched region, then language, then `default-language`:
 *   `pt_BR` → `pt-BR` → `pt` → the default ([languageFor]).
 * - A message missing from a language (a file that couldn't be read) comes from
 *   `default-language`, then English.
 */
public class Translations private constructor(
    public val catalog: MessageCatalog,
    /** Every language, English always among them. */
    public val languages: List<Language>,
    /** `default-language`, for the console and readers whose language has no file. */
    public val default: Language,
    private val fixed: Language?,
    public val theme: Theme,
    /** What was wrong with the settings, such as a `default-language` with no file. */
    public val problems: List<LangProblem>,
) {
    private val byTag: Map<LanguageTag, Language> = languages.associateBy { it.tag }

    // Client locales already matched. Clients send what they like, so it stops growing at
    // MAX_CACHED entries; past that, a locale is matched each time.
    @Volatile
    private var matched: Map<String, Language> = emptyMap()

    /** The language with exactly this [tag], or `null` if there's no file for it. */
    public fun language(tag: LanguageTag): Language? = byTag[tag]

    /**
     * The language to draw in for a reader whose client says [clientLocale] (`pt_br`, as
     * Minecraft sends it), or `null` for the console or a client that hasn't said yet: with
     * `language: auto`, the closest file (`pt_BR` → `pt-BR` → `pt`), else `default-language`;
     * with a fixed `language`, that one for every player. `null` always gets `default-language`.
     */
    public fun languageFor(clientLocale: String?): Language {
        if (clientLocale == null) return default
        if (fixed != null) return fixed
        matched[clientLocale]?.let { return it }
        val language = match(clientLocale)
        val current = matched
        if (current.size < MAX_CACHED) matched = current + (clientLocale to language)
        return language
    }

    private fun match(locale: String): Language {
        val tag = LanguageTag.parse(locale) ?: return default
        for (candidate in tag.candidates()) byTag[candidate]?.let { return it }
        return default
    }

    /** [message]'s markup in [language], parsed with the theme's tags. */
    public fun markup(message: MessageKey, language: Language): Markup = language.parsed(slot(message), theme).markup

    /** [message]'s text in [language], as its file has it. */
    public fun text(message: MessageKey, language: Language): String = language.text(slot(message))

    /**
     * [message] in [language] as sent in chat: placeholders filled in, in the theme's base
     * colour. `null` if the message is blank there, which means "send nothing".
     */
    public fun render(message: MessageKey, language: Language, placeholders: Map<String, Placeholder> = emptyMap()): Component? {
        val parsed = language.parsed(slot(message), theme)
        if (parsed.blank) return null
        if (placeholders.isNotEmpty()) return theme.chat(parsed.markup.render(placeholders))
        return parsed.chat ?: theme.chat(parsed.markup.render()).also { parsed.chat = it }
    }

    /** [message] in [language] with [placeholders]: `render(key, language, "player" to Placeholder.text(name))`. */
    public fun render(message: MessageKey, language: Language, vararg placeholders: Pair<String, Placeholder>): Component? =
        render(message, language, placeholders.toMap())

    internal fun slot(message: MessageKey): Int {
        val slot = catalog.slot(message)
        require(slot >= 0) { "message ${message.key} isn't in this plugin's catalog" }
        return slot
    }

    public companion object {
        private const val MAX_CACHED = 256

        /**
         * Builds the translations from what each language's file says ([files]: by tag, each
         * message's text by key, as [LangLoad.messages] has it). English is always there, from
         * the code if not from a file. A `default-language` or fixed `language` with no file is
         * reported in [problems], and English used.
         */
        public fun of(
            catalog: MessageCatalog,
            files: Map<LanguageTag, Map<String, String>>,
            settings: LanguageSettings = LanguageSettings.DEFAULT,
            theme: Theme = Theme.DEFAULT,
        ): Translations {
            val problems = mutableListOf<LangProblem>()
            val tags = LinkedHashSet<LanguageTag>()
            tags += LanguageTag.ENGLISH
            tags += files.keys
            val english = files[LanguageTag.ENGLISH]
            val defaultFile = files[settings.default] ?: run {
                if (settings.default != LanguageTag.ENGLISH) {
                    problems += LangProblem(
                        LangProblem.Kind.NO_SUCH_LANGUAGE, settings.default, "", 0,
                        "default-language is ${settings.default}, but there's no lang/${settings.default}.yml; using English.",
                    )
                }
                english
            }
            val languages = tags.mapIndexed { index, tag ->
                val own = files[tag]
                val texts = Array(catalog.size) { slot ->
                    val key = catalog.messages[slot].key
                    own?.get(key) ?: defaultFile?.get(key) ?: english?.get(key) ?: catalog.messages[slot].english
                }
                Language(tag, index, texts)
            }
            val byTag = languages.associateBy { it.tag }
            val default = byTag[settings.default] ?: byTag.getValue(LanguageTag.ENGLISH)
            val fixed = when (val choice = settings.choice) {
                LanguageChoice.Auto -> null
                is LanguageChoice.Fixed -> byTag[choice.tag] ?: run {
                    val tag = choice.tag.candidates().firstOrNull { it in byTag }
                    if (tag == null) {
                        problems += LangProblem(
                            LangProblem.Kind.NO_SUCH_LANGUAGE, choice.tag, "", 0,
                            "language is ${choice.tag}, but there's no lang/${choice.tag}.yml; using default-language (${default.tag}).",
                        )
                    }
                    tag?.let { byTag.getValue(it) } ?: default
                }
            }
            return Translations(catalog, languages, default, fixed, theme, problems)
        }
    }
}
