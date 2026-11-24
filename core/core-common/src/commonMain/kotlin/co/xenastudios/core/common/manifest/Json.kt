package co.xenastudios.core.common.manifest

import kotlin.math.abs
import kotlin.math.floor

/**
 * Just enough JSON for the manifest and its schema: values, and a writer whose output is the
 * same on every target and every run (keys in the order they're given, two-space indents,
 * whole numbers without `.0`, a newline at the end), so a committed manifest only changes
 * when what it describes does. Nothing reads JSON here.
 */
internal sealed class Json {
    class Str(val value: String) : Json()

    class Num(val value: Double) : Json()

    class Bool(val value: Boolean) : Json()

    class Arr(val items: List<Json>) : Json()

    /** An object, its members in order, and the [Shape] that built it, if one did. */
    class Obj(val members: List<Pair<String, Json>>, val shape: Shape? = null) : Json() {
        operator fun get(key: String): Json? = members.firstOrNull { it.first == key }?.second
    }

    data object Null : Json()

    /** Written as the files have it. */
    fun write(): String = StringBuilder().also { write(it, 0) }.append('\n').toString()

    private fun write(out: StringBuilder, indent: Int) {
        when (this) {
            is Str -> quote(value, out)
            is Num -> out.append(number(value))
            is Bool -> out.append(value)
            Null -> out.append("null")
            is Arr -> block(out, indent, '[', ']', items) { item -> item.write(out, indent + 1) }
            is Obj -> block(out, indent, '{', '}', members) { (key, value) ->
                quote(key, out)
                out.append(": ")
                value.write(out, indent + 1)
            }
        }
    }

    private inline fun <T> block(out: StringBuilder, indent: Int, open: Char, close: Char, items: List<T>, each: (T) -> Unit) {
        out.append(open)
        if (items.isEmpty()) {
            out.append(close)
            return
        }
        items.forEachIndexed { i, item ->
            out.append(if (i == 0) "\n" else ",\n")
            repeat(indent + 1) { out.append("  ") }
            each(item)
        }
        out.append('\n')
        repeat(indent) { out.append("  ") }
        out.append(close)
    }

    companion object {
        fun of(value: String?): Json = value?.let { Str(it) } ?: Null

        fun of(value: Boolean): Json = Bool(value)

        fun of(value: Int): Json = Num(value.toDouble())

        fun of(value: Double?): Json = value?.let { Num(it) } ?: Null

        fun strings(values: Iterable<String>): Json = Arr(values.map { Str(it) })

        fun obj(vararg members: Pair<String, Json>): Obj = Obj(members.toList())

        /** Whole numbers as integers, so 3.0 is written 3 on every target. */
        private fun number(value: Double): String {
            require(value.isFinite()) { "JSON has no $value" }
            return if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
        }

        private fun quote(text: String, out: StringBuilder) {
            out.append('"')
            for (c in text) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
                }
            }
            out.append('"')
        }
    }
}
