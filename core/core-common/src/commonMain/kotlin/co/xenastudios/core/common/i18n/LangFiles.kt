package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigError
import co.xenastudios.core.common.config.ConfigWriter
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.config.YamlReader

/**
 * A translation as the jar ships it, `lang/<tag>.yml`: the [messages] it translates (it may
 * leave some to English), the texts it used to have for each ([former]), its [name] in its
 * own language, and whether it's [machineDrafted] and not yet reviewed.
 *
 * In the file, the messages are flat `key: "text"` lines, and the rest sits under the
 * reserved [LangFiles.RESERVED] section:
 *
 * ```
 * homes.set: "<prefix>Zuhause <highlight><name></highlight> gesetzt."
 * translation:
 *   name: "Deutsch"
 *   machine-drafted: true
 *   former:
 *     homes.set: ["<prefix>Home <highlight><name></highlight> gesetzt."]
 * ```
 *
 * When a translation changes, its old text goes into `former`, so owners who never edited
 * the message get the new one, as [MessageKey.formerEnglish] does for English.
 */
public class ShippedLanguage(
    public val tag: LanguageTag,
    public val messages: Map<String, String>,
    public val former: Map<String, List<String>> = emptyMap(),
    public val name: String? = null,
    public val machineDrafted: Boolean = false,
)

/**
 * Reads and writes the `lang/<tag>.yml` files in a plugin's folder against a
 * [MessageCatalog]. Text in, text out, as [co.xenastudios.core.common.config.Configs] does:
 * the platform reads and writes the files (on the JVM, `core-jvm`'s `LangFolder`).
 *
 * What it keeps true:
 * - Every language the jar ships is written in full, with each message's comment, and
 *   owners edit the language they want in place. A file that's deleted is written again.
 * - A message the file doesn't have is added: in the shipped translation, or in English
 *   where there's none, so the owner sees what's left to translate.
 * - A message nobody edited follows updates: if the file still has a text the plugin once
 *   shipped for it (a former translation, the English it was written in before a
 *   translation came, or a former English text), it gets the current one. An edited one is
 *   kept.
 * - Keys it doesn't know are reported and kept; a value that isn't text is reported, kept in
 *   the file, and the shipped text is used.
 * - A file is written only when loading changed it.
 */
public object LangFiles {

    /** The top-level key of the shipped files' own settings, which no message key may start with. */
    public const val RESERVED: String = "translation"

    private val PLAIN_KEY = Regex("[a-z0-9][a-z0-9_.-]*")

    /** Reads a shipped translation, `lang/<tag>.yml` from the jar. */
    public fun readShipped(tag: LanguageTag, text: String, reader: YamlReader): Outcome<ShippedLanguage, ConfigError> {
        val root = when (val parsed = parse(text, reader, "lang/$tag.yml")) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> return parsed
        }
        val entries = LinkedHashMap<String, YamlNode.Mapping.Entry>()
        flatten(root, "", entries)
        val messages = LinkedHashMap<String, String>()
        for ((key, entry) in entries) text(entry.value)?.let { messages[key] = it }
        val meta = root[RESERVED] as? YamlNode.Mapping
        val former = LinkedHashMap<String, List<String>>()
        val formerNode = meta?.get("former") as? YamlNode.Mapping
        if (formerNode != null) {
            val flat = LinkedHashMap<String, YamlNode.Mapping.Entry>()
            flatten(formerNode, "", flat, reserved = false)
            for ((key, entry) in flat) {
                former[key] = when (val value = entry.value) {
                    is YamlNode.Sequence -> value.items.mapNotNull { text(it) }
                    else -> listOfNotNull(text(value))
                }
            }
        }
        val name = (meta?.get("name") as? YamlNode.Scalar)?.content
        val drafted = (meta?.get("machine-drafted") as? YamlNode.Scalar)?.content?.trim()?.lowercase() == "true"
        return Outcome.Ok(ShippedLanguage(tag, messages, former, name, drafted))
    }

    /** The full file for [tag], as written on first start: the shipped translation, English where it has none. */
    public fun defaultText(catalog: MessageCatalog, tag: LanguageTag, shipped: ShippedLanguage?): String =
        write(catalog, tag, shipped, emptyMap(), emptySet())

    /**
     * Reads [text], the file `lang/<tag>.yml` in the plugin's folder (`null` if there's none),
     * against [catalog] and the jar's translation for [tag] ([shipped], `null` if the jar has
     * none: English, or a language the owner added). What to write back, if anything, is in
     * [LangLoad.write]. An error means the file isn't YAML it can read: leave the file as it
     * is, and use the shipped text for this language.
     */
    public fun load(
        catalog: MessageCatalog,
        tag: LanguageTag,
        shipped: ShippedLanguage?,
        text: String?,
        reader: YamlReader,
    ): Outcome<LangLoad, ConfigError> {
        val file = "lang/$tag.yml"
        if (text == null) {
            val messages = LinkedHashMap<String, String>()
            for (message in catalog.messages) messages[message.key] = target(message, shipped)
            val notice = LangNotice(LangNotice.Kind.WROTE, "Wrote $file.")
            return Outcome.Ok(LangLoad(tag, messages, emptyList(), listOf(notice), defaultText(catalog, tag, shipped)))
        }
        val root = when (val parsed = parse(text, reader, file)) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> return parsed
        }
        val entries = LinkedHashMap<String, YamlNode.Mapping.Entry>()
        flatten(root, "", entries)

        val messages = LinkedHashMap<String, String>()
        val problems = mutableListOf<LangProblem>()
        val missing = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val replaced = HashMap<String, String>()
        for (message in catalog.messages) {
            val target = target(message, shipped)
            val entry = entries[message.key]
            if (entry == null) {
                missing += message.key
                messages[message.key] = target
                replaced[message.key] = target
                continue
            }
            val value = text(entry.value)
            when {
                value == null -> {
                    problems += LangProblem(
                        LangProblem.Kind.NOT_TEXT, tag, message.key, entry.line,
                        "$file: ${message.key} should be text in quotes, not a list or a section; using the shipped text.",
                    )
                    messages[message.key] = target
                }
                value != target && value in formerTexts(message, shipped, target) -> {
                    updated += message.key
                    messages[message.key] = target
                    replaced[message.key] = target
                }
                else -> {
                    messages[message.key] = value
                    for (name in TranslationCheck.foreignTags(message, value, interactive = true)) {
                        problems += LangProblem(
                            LangProblem.Kind.FOREIGN_TAG, tag, message.key, entry.line,
                            "$file: ${message.key} uses <$name>, which this message doesn't fill in; it shows as text.",
                        )
                    }
                }
            }
        }
        val unknown = entries.keys.filter { catalog[it] == null }
        for (key in unknown) {
            problems += LangProblem(
                LangProblem.Kind.UNKNOWN_KEY, tag, key, entries.getValue(key).line,
                "$file: $key isn't a message of this version (a typo?); it's left in the file and ignored.",
            )
        }

        val notices = mutableListOf<LangNotice>()
        if (missing.isNotEmpty()) {
            val n = missing.size
            notices += LangNotice(LangNotice.Kind.ADDED, "Added $n new message${if (n == 1) "" else "s"} to $file.", missing)
        }
        if (updated.isNotEmpty()) {
            val n = updated.size
            notices += LangNotice(
                LangNotice.Kind.UPDATED,
                "Gave $n message${if (n == 1) "" else "s"} you hadn't edited in $file the new text.",
                updated,
            )
        }
        val write = if (missing.isEmpty() && updated.isEmpty()) {
            null
        } else {
            val kept = LinkedHashMap<String, YamlNode>()
            for ((key, entry) in entries) kept[key] = entry.value
            for ((key, value) in replaced) kept[key] = YamlNode.Scalar(value)
            write(catalog, tag, shipped, kept, unknown.toSet())
        }
        return Outcome.Ok(LangLoad(tag, messages, problems, notices, write))
    }

    /** What a file gets for [message] when it doesn't say: the shipped translation, or English. */
    internal fun target(message: MessageKey, shipped: ShippedLanguage?): String = shipped?.messages?.get(message.key) ?: message.english

    // Texts the plugin once wrote for this message in this language: a file still on one was never edited.
    private fun formerTexts(message: MessageKey, shipped: ShippedLanguage?, target: String): Set<String> {
        val out = HashSet<String>()
        shipped?.former?.get(message.key)?.let { out += it }
        out += message.formerEnglish
        // English stood in until the translation came.
        if (target != message.english) out += message.english
        return out
    }

    private fun parse(text: String, reader: YamlReader, file: String): Outcome<YamlNode.Mapping, ConfigError> {
        val node = when (val read = reader.read(text)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return Outcome.Err(ConfigError("$file isn't valid YAML: ${read.error.message}", read.error.line, read.error.column))
        }
        return when (node) {
            is YamlNode.Mapping -> Outcome.Ok(node)
            is YamlNode.Null -> Outcome.Ok(YamlNode.Mapping(emptyList(), node.line))
            else -> Outcome.Err(ConfigError("$file should be messages (key: \"text\"), not a single value or a list.", node.line))
        }
    }

    // Flat `homes.set: "..."` lines, as written; a nested `homes:` section reads the same.
    private fun flatten(
        node: YamlNode.Mapping,
        prefix: String,
        out: MutableMap<String, YamlNode.Mapping.Entry>,
        reserved: Boolean = true,
    ) {
        for (entry in node.entries) {
            if (reserved && prefix.isEmpty() && entry.key == RESERVED) continue
            val key = if (prefix.isEmpty()) entry.key else "$prefix.${entry.key}"
            val value = entry.value
            if (value is YamlNode.Mapping && value.entries.isNotEmpty()) flatten(value, key, out, reserved) else out[key] = YamlNode.Mapping.Entry(key, value, entry.line)
        }
    }

    // A message's text: `key:` with nothing is a blank message, which sends nothing.
    private fun text(node: YamlNode): String? = when (node) {
        is YamlNode.Scalar -> node.content
        is YamlNode.Null -> ""
        else -> null
    }

    private fun write(
        catalog: MessageCatalog,
        tag: LanguageTag,
        shipped: ShippedLanguage?,
        values: Map<String, YamlNode>,
        unknown: Set<String>,
    ): String {
        val out = StringBuilder()
        val name = shipped?.name ?: if (tag == LanguageTag.ENGLISH) "English" else null
        comment(out, "Messages in ${if (name != null) "$name ($tag)" else tag.toString()}. Edit any of them here: your edits are kept,")
        comment(out, "and a message you haven't edited gets the new text when an update changes it. Delete")
        comment(out, "this file to have it written again as shipped. <prefix>, <highlight>, <success> and")
        comment(out, "<error> are the theme's, set in config.yml; each message's own placeholders are listed")
        comment(out, "above it. A blank message (\"\") sends nothing.")
        if (shipped?.machineDrafted == true) {
            comment(out, "")
            comment(out, "Drafted by machine translation and not yet reviewed by a native speaker.")
            comment(out, "Corrections are welcome.")
        }
        var group: String? = null
        for (message in catalog.messages) {
            val first = message.key.substringBefore('.')
            if (first != group || message.comment.isNotEmpty()) out.append('\n')
            group = first
            for (line in message.comment) comment(out, line)
            if (message.placeholders.isNotEmpty()) comment(out, "Placeholders: " + message.placeholders.joinToString(", ") { "<$it>" })
            val node = values[message.key] ?: YamlNode.Scalar(target(message, shipped))
            entry(out, message.key, node)
        }
        if (unknown.isNotEmpty()) out.append('\n')
        for (key in unknown) entry(out, key, values.getValue(key))
        return out.toString()
    }

    private fun entry(out: StringBuilder, key: String, node: YamlNode) {
        out.append(if (PLAIN_KEY.matches(key)) key else ConfigWriter.quote(key)).append(": ")
        out.append(
            when (node) {
                is YamlNode.Scalar -> ConfigWriter.quote(node.content)
                is YamlNode.Null -> "\"\""
                else -> ConfigWriter.inline(node, false)
            },
        ).append('\n')
    }

    private fun comment(out: StringBuilder, line: String) {
        out.append(if (line.isEmpty()) "#" else "# $line").append('\n')
    }
}

/**
 * What [LangFiles.load] made of one language's file: every message's text ([messages], by
 * key, none missing), the [problems] it worked around, the [notices] of what it changed, and
 * the text to [write] back (`null` if nothing should be written).
 */
public class LangLoad internal constructor(
    public val tag: LanguageTag,
    public val messages: Map<String, String>,
    public val problems: List<LangProblem>,
    public val notices: List<LangNotice>,
    public val write: String?,
)

/** Something wrong in a language file, worked around: the [message] says what and how. */
public data class LangProblem(
    public val kind: Kind,
    public val language: LanguageTag,
    public val key: String,
    public val line: Int,
    public val message: String,
) {
    /** The kinds of problem. */
    public enum class Kind {
        /** A key no message has, probably a typo: kept in the file and ignored. */
        UNKNOWN_KEY,

        /** A list or a section where a message's text belongs: the shipped text is used. */
        NOT_TEXT,

        /** An edit uses a tag the message doesn't fill in: it's used, and the tag shows as text. */
        FOREIGN_TAG,

        /** The file couldn't be read or isn't valid YAML: the shipped texts are used. */
        UNREADABLE,

        /** The file couldn't be written: what was read is used anyway. */
        SAVE_FAILED,

        /** `default-language` or `language` names a language with no file: English is used. */
        NO_SUCH_LANGUAGE,
    }

    override fun toString(): String = message
}

/** Something loading a language file changed, for the log. */
public data class LangNotice(public val kind: Kind, public val message: String, public val keys: List<String> = emptyList()) {
    /** The kinds of notice. */
    public enum class Kind {
        /** There was no file, so it was written in full. */
        WROTE,

        /** Messages the file didn't have were added. */
        ADDED,

        /** Messages still on a text the plugin once shipped were given the current one. */
        UPDATED,
    }

    override fun toString(): String = message
}
