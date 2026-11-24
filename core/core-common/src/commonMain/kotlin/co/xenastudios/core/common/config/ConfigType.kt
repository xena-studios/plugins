package co.xenastudios.core.common.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.MarkupTag
import co.xenastudios.core.common.time.DurationError
import co.xenastudios.core.common.time.Durations
import kotlin.time.Duration

/**
 * What a setting holds, and how it's read from and written to YAML. The built-in types are
 * here; a plugin can make its own with [scalar].
 */
public abstract class ConfigType<T : Any> {

    /**
     * The type's name in the docs and the schema dump: `string`, `integer`, `duration`,
     * `list<string>`, ...
     */
    public abstract val name: String

    /** Whether its values are written bare (`5`, `true`) rather than in quotes. */
    public open val plain: Boolean get() = false

    /** For a type with a fixed set of values, those values as written. */
    public open val choices: List<String> get() = emptyList()

    /** What `key:` with nothing after it means, or `null` if it's a mistake (an empty number). */
    public open fun empty(): T? = null

    /**
     * Reads [node], or says why it can't be used, in a few words that follow the setting's
     * path: `should be a whole number`. Never throws.
     */
    public abstract fun decode(node: YamlNode, context: ConfigContext): Outcome<T, String>

    /** [value] as YAML: what the file says for it. [decode] reads it back to [value]. */
    public abstract fun encode(value: T): YamlNode

    override fun toString(): String = name

    public companion object {
        /** Any text. */
        public val STRING: ConfigType<String> = text("string", plain = false, empty = "") { Outcome.Ok(it) }

        /** A whole number that fits in an `Int`. */
        public val INT: ConfigType<Int> = text("integer", plain = true) { raw ->
            when (val number = wholeNumber(raw)) {
                null -> Outcome.Err("should be a whole number")
                !in Int.MIN_VALUE..Int.MAX_VALUE -> Outcome.Err("is too large")
                else -> Outcome.Ok(number.toInt())
            }
        }

        /** A whole number that fits in a `Long`. */
        public val LONG: ConfigType<Long> = text("integer", plain = true) { raw ->
            wholeNumber(raw)?.let { Outcome.Ok(it) } ?: Outcome.Err("should be a whole number")
        }

        /** A number, which can have a fraction. Not NaN or infinite. */
        public val DOUBLE: ConfigType<Double> = text("number", plain = true, write = ::number) { raw ->
            val number = raw.trim().toDoubleOrNull()
            if (number == null || number.isNaN() || number.isInfinite()) Outcome.Err("should be a number") else Outcome.Ok(number)
        }

        /** `true` or `false`; `yes`, `no`, `on` and `off` are read too, as today's plugins read them. */
        public val BOOLEAN: ConfigType<Boolean> = text("boolean", plain = true) { raw ->
            when (raw.trim().lowercase()) {
                "true", "yes", "on" -> Outcome.Ok(true)
                "false", "no", "off" -> Outcome.Ok(false)
                else -> Outcome.Err("should be true or false")
            }
        }

        /** A length of time as [Durations] reads it (`30s`, `1h30m`), written exactly by [Durations.write]. */
        public val DURATION: ConfigType<Duration> = text("duration", plain = false, write = Durations::write) { raw ->
            when (val parsed = Durations.parse(raw)) {
                is Outcome.Ok -> Outcome.Ok(parsed.value)
                is Outcome.Err -> Outcome.Err(durationReason(parsed.error))
            }
        }

        /** A message in our markup, parsed once with the theme's tags ([ConfigContext.markupTags]). */
        public val MARKUP: ConfigType<Markup> = object : ConfigType<Markup>() {
            override val name: String get() = "markup"
            override fun empty(): Markup = Markup.EMPTY
            override fun decode(node: YamlNode, context: ConfigContext): Outcome<Markup, String> = when (node) {
                is YamlNode.Scalar -> Outcome.Ok(Markup.parse(node.content, context.markupTags))
                else -> Outcome.Err(SINGLE)
            }
            override fun encode(value: Markup): YamlNode = YamlNode.Scalar(value.source)
        }

        /** One of [values], written in lower case with `-` for `_` (`SQLITE` is `sqlite`, `HIGH_FIRST` is `high-first`). */
        public fun <E : Enum<E>> enumOf(values: List<E>): ConfigType<E> = EnumType(values)

        /** A list of [element]s. One value where a list belongs is a list of one; an item that can't be read is skipped. */
        public fun <T : Any> listOf(element: ConfigType<T>): ConfigType<List<T>> = ListType(element)

        /**
         * A type of the plugin's own, written as one value: [read] turns the text into a value
         * or says why it can't (`should be a colour like #B48CFF`), and [write] turns a value
         * back into that text.
         */
        public fun <T : Any> scalar(name: String, write: (T) -> String, read: (String) -> Outcome<T, String>): ConfigType<T> =
            text(name, plain = false, write = write, read = read)

        internal const val SINGLE = "should be a single value"

        private fun <T : Any> text(
            name: String,
            plain: Boolean,
            write: (T) -> String = { it.toString() },
            empty: T? = null,
            read: (String) -> Outcome<T, String>,
        ): ConfigType<T> = object : ConfigType<T>() {
            override val name: String get() = name
            override val plain: Boolean get() = plain
            override fun empty(): T? = empty
            override fun decode(node: YamlNode, context: ConfigContext): Outcome<T, String> = when (node) {
                is YamlNode.Scalar -> read(node.content)
                else -> Outcome.Err(SINGLE)
            }
            override fun encode(value: T): YamlNode = YamlNode.Scalar(write(value))
        }

        private fun wholeNumber(raw: String): Long? {
            val text = raw.trim()
            text.toLongOrNull()?.let { return it }
            // "5.0" is a whole number too, as today's plugins read it.
            val number = text.toDoubleOrNull() ?: return null
            return if (number.isFinite() && number == kotlin.math.floor(number) && kotlin.math.abs(number) < 9.0E15) number.toLong() else null
        }

        internal fun number(value: Double): String =
            if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1.0E15) value.toLong().toString() else value.toString()

        private fun durationReason(error: DurationError): String = when (error.kind) {
            DurationError.Kind.TOO_LONG -> "is over 100 years"
            DurationError.Kind.UNKNOWN_UNIT -> "has a unit that isn't one (\"${error.found}\"); use ms, s, m, h, d, w, mo or y"
            else -> "should be a length of time like 30s, 5m or 1h30m"
        }
    }

    private class EnumType<E : Enum<E>>(private val values: List<E>) : ConfigType<E>() {
        override val name: String get() = "enum"
        override val choices: List<String> = values.map { written(it) }
        override fun decode(node: YamlNode, context: ConfigContext): Outcome<E, String> {
            if (node !is YamlNode.Scalar) return Outcome.Err(SINGLE)
            val wanted = node.content.trim().replace('-', '_').uppercase()
            return values.firstOrNull { it.name == wanted }?.let { Outcome.Ok(it) }
                ?: Outcome.Err("should be one of ${choices.joinToString(", ")}")
        }
        override fun encode(value: E): YamlNode = YamlNode.Scalar(written(value))
        private fun written(value: E) = value.name.lowercase().replace('_', '-')
    }

    internal class ListType<T : Any>(internal val element: ConfigType<T>) : ConfigType<List<T>>() {
        override val name: String get() = "list<${element.name}>"
        override val choices: List<String> get() = element.choices
        override fun empty(): List<T> = emptyList()
        override fun decode(node: YamlNode, context: ConfigContext): Outcome<List<T>, String> {
            val items = when (node) {
                is YamlNode.Sequence -> node.items
                is YamlNode.Mapping -> return Outcome.Err("should be a list")
                else -> listOf(node)
            }
            return Outcome.Ok(items.mapNotNull { (element.decode(it, context) as? Outcome.Ok)?.value })
        }
        override fun encode(value: List<T>): YamlNode = YamlNode.Sequence(value.map { element.encode(it) })
    }
}

/** What reading a config needs besides the file: the theme's markup tags, which messages are parsed with. */
public class ConfigContext(public val markupTags: Map<String, MarkupTag> = emptyMap()) {
    public companion object {
        /** No theme tags. */
        public val DEFAULT: ConfigContext = ConfigContext()
    }
}
