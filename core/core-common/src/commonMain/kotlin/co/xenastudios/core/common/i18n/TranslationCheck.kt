package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlReader

/**
 * The check CI runs on every translation a plugin ships, against its English messages: a
 * broken translation fails the build, not a server. The build's `checkTranslations` task runs
 * [check] on every `lang/<tag>.yml` a plugin ships (`core-jvm`'s `TranslationCheckMain`), and a
 * plugin's tests can call [verify] too. It finds:
 * - [TranslationIssue.Kind.MISSING]: a message the translation doesn't have;
 * - [TranslationIssue.Kind.UNKNOWN_KEY]: a key no message has, also in `former`;
 * - [TranslationIssue.Kind.FOREIGN_TAG]: a tag the English message doesn't fill in: a
 *   placeholder it doesn't declare, a typo, or a `<click>` or `<hover>` its English hasn't;
 * - [TranslationIssue.Kind.DROPPED_PLACEHOLDER]: a placeholder the English uses and the
 *   translation leaves out, so a value would be missing;
 * - [TranslationIssue.Kind.INVALID_FILE]: a file that isn't YAML it can read.
 *
 * Styles and the theme's tags may change freely: a language may bold another word.
 */
public object TranslationCheck {

    /** Every issue in [shipped]; [allowMissing] lets a partial translation pass (English fills in). */
    public fun check(catalog: MessageCatalog, shipped: ShippedLanguage, allowMissing: Boolean = false): List<TranslationIssue> {
        val tag = shipped.tag
        val issues = mutableListOf<TranslationIssue>()
        for (message in catalog.messages) {
            val text = shipped.messages[message.key]
            if (text == null) {
                if (!allowMissing) issues += TranslationIssue(TranslationIssue.Kind.MISSING, tag, message.key, "lang/$tag.yml has no ${message.key}")
                continue
            }
            for (name in foreignTags(message, text)) {
                issues += TranslationIssue(
                    TranslationIssue.Kind.FOREIGN_TAG, tag, message.key,
                    "lang/$tag.yml: ${message.key} uses <$name>, which its English message doesn't fill in",
                )
            }
            for (name in droppedPlaceholders(message, text)) {
                issues += TranslationIssue(
                    TranslationIssue.Kind.DROPPED_PLACEHOLDER, tag, message.key,
                    "lang/$tag.yml: ${message.key} leaves out <$name>, which its English message shows",
                )
            }
        }
        for (key in shipped.messages.keys) {
            if (catalog[key] == null) issues += TranslationIssue(TranslationIssue.Kind.UNKNOWN_KEY, tag, key, "lang/$tag.yml: $key isn't a message")
        }
        for (key in shipped.former.keys) {
            if (catalog[key] == null) {
                issues += TranslationIssue(TranslationIssue.Kind.UNKNOWN_KEY, tag, key, "lang/$tag.yml: ${LangFiles.RESERVED}.former.$key isn't a message")
            }
        }
        return issues
    }

    /** Every issue in [text], the shipped file `lang/<tag>.yml`. */
    public fun check(
        catalog: MessageCatalog,
        tag: LanguageTag,
        text: String,
        reader: YamlReader,
        allowMissing: Boolean = false,
    ): List<TranslationIssue> = when (val read = LangFiles.readShipped(tag, text, reader)) {
        is Outcome.Ok -> check(catalog, read.value, allowMissing)
        is Outcome.Err -> listOf(TranslationIssue(TranslationIssue.Kind.INVALID_FILE, tag, "", read.error.toString()))
    }

    /** Fails, listing every issue, if any of [files] (by tag, the file's text) has one: for a plugin's tests. */
    public fun verify(catalog: MessageCatalog, files: Map<LanguageTag, String>, reader: YamlReader, allowMissing: Boolean = false) {
        val issues = files.flatMap { (tag, text) -> check(catalog, tag, text, reader, allowMissing) }
        if (issues.isNotEmpty()) {
            throw AssertionError("${issues.size} translation issue${if (issues.size == 1) "" else "s"}:\n" + issues.joinToString("\n") { "  $it" })
        }
    }

    /**
     * Tags [text] uses that [message]'s English doesn't fill in, each once. With
     * [interactive], a `<click>` or `<hover>` the English hasn't is fine: an owner may add one.
     */
    internal fun foreignTags(message: MessageKey, text: String, interactive: Boolean = false): List<String> {
        val english by lazy { MarkupTags.names(message.english).toSet() }
        return MarkupTags.names(text).distinct().filter { name ->
            when (MarkupTags.kind(name)) {
                MarkupTags.Kind.STYLE, MarkupTags.Kind.THEME -> false
                MarkupTags.Kind.INTERACTIVE -> !interactive && name !in english
                MarkupTags.Kind.OTHER -> name !in message.placeholders
            }
        }
    }

    /**
     * Placeholders [message]'s English uses that [text] leaves out. One it only offers
     * ([MessageKey.optional]) may be left out, as the English does.
     */
    internal fun droppedPlaceholders(message: MessageKey, text: String): List<String> {
        if (message.placeholders.isEmpty()) return emptyList()
        val used = MarkupTags.names(text).toSet()
        return message.placeholders.filter { it !in used && it !in message.optional }
    }
}

/** One thing wrong with a shipped translation: its [kind], the [language], the message's [key], and what to fix. */
public data class TranslationIssue(
    public val kind: Kind,
    public val language: LanguageTag,
    public val key: String,
    public val message: String,
) {
    /** The kinds of issue, described on [TranslationCheck]. */
    public enum class Kind { MISSING, UNKNOWN_KEY, FOREIGN_TAG, DROPPED_PLACEHOLDER, INVALID_FILE }

    override fun toString(): String = message
}
