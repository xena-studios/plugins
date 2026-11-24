package co.xenastudios.core.common.admin

import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.Formats
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.Theme

/**
 * Draws the admin subcommands' replies ([co.xenastudios.core.common.i18n.CoreMessages], and a
 * plugin's own health checks' messages) for whoever ran the command, in their language.
 * Placeholder values that are numbers are written for that language (`1.234` in German);
 * anything else is text.
 *
 * The command source doesn't carry a language yet, so the adapter says which client locale a
 * source has ([of]); until it does, [ENGLISH].
 */
public fun interface AdminText {
    /** [message] for [source] with [placeholders], or `null` if it's blank in their language (send nothing). */
    public fun render(source: CommandSource, message: MessageKey, placeholders: Map<String, Any>): Component?

    public companion object {
        /** Every reply in English, from the code, in the default theme. */
        public val ENGLISH: AdminText = AdminText { _, message, placeholders -> english(message, placeholders) }

        /**
         * Replies from the plugin's [translations], in the language [locale] gives for each
         * source (the client's locale, `null` for the console). A message the plugin's catalog
         * doesn't have is drawn in English.
         */
        public fun of(translations: Translations, locale: (CommandSource) -> String?): AdminText = AdminText { source, message, placeholders ->
            if (translations.catalog.slot(message) < 0) {
                english(message, placeholders, translations.theme)
            } else {
                val language = translations.languageFor(locale(source))
                translations.render(message, language, filled(placeholders, language.formats))
            }
        }

        /** [message]'s English text with [placeholders], as plain text: for the console and the debug report. */
        public fun plainEnglish(message: MessageKey, placeholders: Map<String, Any>): String =
            english(message, placeholders)?.plain() ?: ""

        // On first use: AdminText.ENGLISH is made as a plugin enables, and the formats table
        // (every language's) has no business on the server thread before a reply needs it.
        private val englishFormats by lazy { Formats.of(co.xenastudios.core.common.i18n.LanguageTag.ENGLISH) }

        private fun english(message: MessageKey, placeholders: Map<String, Any>, theme: Theme = Theme.DEFAULT): Component? {
            if (message.english.isBlank()) return null
            return theme.chat(Markup.parse(message.english, theme.tags).render(filled(placeholders, englishFormats)))
        }

        private fun filled(placeholders: Map<String, Any>, formats: Formats): Map<String, Placeholder> =
            placeholders.mapValues { (_, value) ->
                when (value) {
                    is Placeholder -> value
                    is Int -> Placeholder.text(formats.number(value))
                    is Long -> Placeholder.text(formats.number(value))
                    is Component -> Placeholder.component(value)
                    else -> Placeholder.text(value)
                }
            }
    }
}
