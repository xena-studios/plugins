package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigType

/**
 * A language as a file and a config name it: `en`, `de`, `pt-BR`, `zh-CN`. Read from what a
 * client sends (`pt_br`, `zh_CN`) or an owner writes (`pt-br`), and always written the one
 * way: the language in lower case, a script in title case, a region in upper case, joined
 * with `-`, as in BCP 47. Anything after the region (variants, extensions) is dropped.
 */
public class LanguageTag private constructor(
    /** The language: `pt`. */
    public val language: String,
    /** The script, if any: `Hant` in `zh-Hant-TW`. */
    public val script: String?,
    /** The region, if any: `BR`. */
    public val region: String?,
) {
    private val text: String = buildString {
        append(language)
        if (script != null) append('-').append(script)
        if (region != null) append('-').append(region)
    }

    /**
     * The tags to try for this one, most specific first: `zh-Hant-TW`, `zh-TW`, `zh-Hant`,
     * `zh`; `pt-BR`, `pt`. A file is looked up for each in turn, then `default-language`.
     */
    public fun candidates(): List<LanguageTag> {
        val out = ArrayList<LanguageTag>(4)
        out += this
        if (script != null && region != null) out += LanguageTag(language, null, region)
        if (script != null) out += LanguageTag(language, script, null)
        if (script != null || region != null) out += LanguageTag(language, null, null)
        return out
    }

    override fun equals(other: Any?): Boolean = other is LanguageTag && other.text == text
    override fun hashCode(): Int = text.hashCode()

    /** The tag as files and configs write it: `pt-BR`. */
    override fun toString(): String = text

    public companion object {
        /** English, the language every message is written in first, and the last fallback. */
        public val ENGLISH: LanguageTag = LanguageTag("en", null, null)

        /**
         * Reads [text] (`pt_BR`, `pt-br`, `zh_Hant_TW`, `EN`), or `null` if it isn't a
         * language tag. A client's locale is whatever it sends, so this never throws.
         */
        public fun parse(text: String): LanguageTag? {
            if (text.isEmpty() || text.length > 35) return null
            val parts = text.split('-', '_')
            if (parts.any { part -> part.isEmpty() || part.length > 8 || !part.all { it.isAsciiLetter() || it in '0'..'9' } }) return null
            val language = parts[0]
            if (language.length !in 2..8 || !language.all { it.isAsciiLetter() }) return null
            var script: String? = null
            var region: String? = null
            var i = 1
            if (i < parts.size && parts[i].length == 4 && parts[i].all { it.isAsciiLetter() }) {
                script = parts[i].lowercase().replaceFirstChar { it.uppercaseChar() }
                i++
            }
            if (i < parts.size) {
                val part = parts[i]
                if ((part.length == 2 && part.all { it.isAsciiLetter() }) || (part.length == 3 && part.all { it in '0'..'9' })) {
                    region = part.uppercase()
                }
            }
            return LanguageTag(language.lowercase(), script, region)
        }

        /** [text] as a tag, for code that knows it's one: `LanguageTag.of("pt-BR")`. */
        public fun of(text: String): LanguageTag = requireNotNull(parse(text)) { "\"$text\" isn't a language tag, such as en or pt-BR" }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        /** A config setting holding a tag (`default-language: en`). */
        public val TYPE: ConfigType<LanguageTag> = ConfigType.scalar("language", { it.toString() }) { raw ->
            parse(raw.trim())?.let { Outcome.Ok(it) } ?: Outcome.Err("isn't a language tag, such as en, de or pt-BR")
        }
    }
}

/**
 * What the `language` setting says: [Auto], each player in their client's language, or one
 * [Fixed] language for every player.
 */
public sealed class LanguageChoice {
    /** Each player sees their client's language, if a file has it, and `default-language` if not. */
    public object Auto : LanguageChoice() {
        override fun toString(): String = "auto"
    }

    /** Every player sees [tag], whatever their client says. The console still gets `default-language`. */
    public data class Fixed(public val tag: LanguageTag) : LanguageChoice() {
        override fun toString(): String = tag.toString()
    }

    public companion object {
        /** A config setting holding `auto` or a tag (`language: auto`, `language: de`). */
        public val TYPE: ConfigType<LanguageChoice> = ConfigType.scalar("language", { it.toString() }) { raw ->
            val text = raw.trim()
            if (text.equals("auto", ignoreCase = true)) {
                Outcome.Ok(Auto)
            } else {
                LanguageTag.parse(text)?.let { Outcome.Ok(Fixed(it)) } ?: Outcome.Err("should be auto or a language tag, such as de or pt-BR")
            }
        }
    }
}

/**
 * The two language settings beside the theme in every config: [choice] (`language`) and
 * [default] (`default-language`), which the console gets, and players whose language has no
 * file.
 */
public class LanguageSettings(
    public val choice: LanguageChoice = LanguageChoice.Auto,
    public val default: LanguageTag = LanguageTag.ENGLISH,
) {
    public companion object {
        /** `language: auto`, `default-language: en`. */
        public val DEFAULT: LanguageSettings = LanguageSettings()

        /** The comment above `language` in a config. */
        public val LANGUAGE_COMMENT: List<String> = listOf(
            "The language players see messages in: auto for each player's own (their client's",
            "language setting), or one language for everyone, such as de or pt-BR.",
        )

        /** The comment above `default-language` in a config. */
        public val DEFAULT_LANGUAGE_COMMENT: List<String> = listOf(
            "The language of the console, and of players whose language has no file in lang/.",
        )
    }
}
