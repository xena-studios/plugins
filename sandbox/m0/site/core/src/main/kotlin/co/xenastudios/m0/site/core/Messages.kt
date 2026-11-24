package co.xenastudios.m0.site.core

/**
 * Every message the plugin sends, by key, with its English text and the tags it fills in.
 * lang/en.yml is generated from these; other languages are files that translate the keys.
 */
open class MessageSpec {
    internal val messages = mutableListOf<Message>()

    class Message internal constructor(val key: String, val english: String, val description: String, val placeholders: List<String>)

    protected fun message(key: String, english: String, description: String, vararg placeholders: String): Message {
        require(messages.none { it.key == key }) { "message $key declared twice" }
        val used = tags(english).toSet()
        placeholders.forEach { require(it in used) { "$key declares <$it> but its English text doesn't use it" } }
        (used - placeholders.toSet() - STYLE_TAGS).forEach { error("$key uses <$it>, which is neither a placeholder nor a style") }
        return Message(key, english, description, placeholders.toList()).also { messages += it }
    }

    companion object {
        /** The markup's style tags, which every message may use. */
        val STYLE_TAGS = setOf("accent", "muted", "error", "success", "b", "i", "u", "reset", "br")

        /** Tag names in a markup string, without closing tags. */
        fun tags(text: String) = Regex("<([a-z][a-z0-9_-]*)>").findAll(text).map { it.groupValues[1] }.toList()
    }
}

/**
 * Reads a shipped translation and checks it against the declarations, as CI will: unknown keys
 * and placeholders a message doesn't fill in fail the build; untranslated keys are reported.
 * The spike's files are flat `key: "text"` lines; the rewrite uses the core's YAML reader.
 */
internal fun checkLanguage(tag: String, text: String, spec: MessageSpec): LanguageDoc {
    val declared = spec.messages.associateBy { it.key }
    val entries = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val key = line.substringBefore(':').trim()
            key to line.substringAfter(':').trim().removeSurrounding("\"")
        }
    val errors = mutableListOf<String>()
    for ((key, value) in entries) {
        val m = declared[key]
        if (m == null) {
            errors += "lang/$tag.yml: unknown key $key"
            continue
        }
        (MessageSpec.tags(value).toSet() - m.placeholders.toSet() - MessageSpec.STYLE_TAGS).forEach {
            errors += "lang/$tag.yml: $key uses <$it>, which $key doesn't fill in"
        }
    }
    check(errors.isEmpty()) { errors.joinToString("\n") }
    val missing = declared.keys.filter { it !in entries }
    return LanguageDoc(tag, declared.size - missing.size, declared.size, missing)
}
