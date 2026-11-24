package co.xenastudios.core.common.config

import co.xenastudios.core.common.Outcome

/**
 * A YAML document as the config reads it: mappings, sequences, scalars and empty values, each
 * with the line it was on. Scalars keep their text only; whether it was quoted doesn't
 * matter, since every setting's type reads it from the text. [line] is 1-based, and 0 for a
 * node that didn't come from a file (a default, or one a migration made).
 *
 * Equality ignores lines, so a node read from a file equals the same node made in code.
 */
public sealed class YamlNode {
    /** The line the node starts on, from 1; 0 if it wasn't read from a file. */
    public abstract val line: Int

    /** A single value, as text: `5`, `true`, `"<red>Hi"` all read as their text. */
    public class Scalar(public val content: String, override val line: Int = 0) : YamlNode() {
        override fun equals(other: Any?): Boolean = other is Scalar && other.content == content
        override fun hashCode(): Int = content.hashCode()
        override fun toString(): String = "Scalar($content)"
    }

    /** A list. */
    public class Sequence(public val items: List<YamlNode>, override val line: Int = 0) : YamlNode() {
        override fun equals(other: Any?): Boolean = other is Sequence && other.items == items
        override fun hashCode(): Int = items.hashCode()
        override fun toString(): String = "Sequence($items)"
    }

    /** A section of settings: keys in the order the file has them. */
    public class Mapping(public val entries: List<Entry>, override val line: Int = 0) : YamlNode() {
        /** The value under [key], or `null` if there's no such key. */
        public operator fun get(key: String): YamlNode? = entries.firstOrNull { it.key == key }?.value

        /** The entry for [key], with the line the key is on. */
        public fun entry(key: String): Entry? = entries.firstOrNull { it.key == key }

        /** Whether the mapping has [key]. */
        public operator fun contains(key: String): Boolean = entries.any { it.key == key }

        /** A copy with [key] set to [value]: in its place if it's there, at the end if not. */
        public fun with(key: String, value: YamlNode): Mapping {
            val index = entries.indexOfFirst { it.key == key }
            if (index < 0) return Mapping(entries + Entry(key, value), line)
            val changed = entries.toMutableList()
            changed[index] = Entry(key, value, entries[index].line)
            return Mapping(changed, line)
        }

        /** A copy without [key]. */
        public fun without(key: String): Mapping = Mapping(entries.filter { it.key != key }, line)

        override fun equals(other: Any?): Boolean = other is Mapping && other.entries == entries
        override fun hashCode(): Int = entries.hashCode()
        override fun toString(): String = "Mapping($entries)"

        /** One `key: value` of a [Mapping], with the line the key is on. */
        public class Entry(public val key: String, public val value: YamlNode, public val line: Int = value.line) {
            override fun equals(other: Any?): Boolean = other is Entry && other.key == key && other.value == value
            override fun hashCode(): Int = 31 * key.hashCode() + value.hashCode()
            override fun toString(): String = "$key=$value"
        }
    }

    /** A key with nothing after it (`permission:`), or `null` or `~`. */
    public class Null(override val line: Int = 0) : YamlNode() {
        override fun equals(other: Any?): Boolean = other is Null
        override fun hashCode(): Int = 0
        override fun toString(): String = "Null"
    }
}

/**
 * Turns YAML text into [YamlNode]s. The config system writes YAML itself (in schema order,
 * with the schema's comments) and only needs this to read it, so a platform gives one
 * reader: snakeyaml-engine-kmp on the JVM (`core-jvm`'s `JvmYaml`). It has no wasmWasi build
 * yet (krzema12/snakeyaml-engine-kmp#523), so `core-common` takes the reader as a parameter
 * rather than depending on one.
 */
public fun interface YamlReader {
    /** The document in [text], or where and why it isn't valid YAML. Never throws. */
    public fun read(text: String): Outcome<YamlNode, YamlSyntaxError>
}

/** Why a document isn't valid YAML: the [message], at [line] and [column] (from 1; 0 if unknown). */
public data class YamlSyntaxError(public val message: String, public val line: Int, public val column: Int = 0)
