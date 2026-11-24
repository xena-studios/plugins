package co.xenastudios.core.common.db

/**
 * One value bound to a statement or read from a row: the five kinds SQLite stores, which every
 * database here can hold. A [ColumnType] turns a Kotlin value into one and back, so nothing else
 * needs to know how a type is stored.
 */
public sealed class SqlValue {
    /** SQL's `NULL`. */
    public data object Null : SqlValue()

    /** A whole number: integers, longs, booleans as 0 and 1. */
    public data class Integer(public val value: Long) : SqlValue()

    /** A floating-point number. */
    public data class Real(public val value: Double) : SqlValue()

    /** Text. */
    public data class Text(public val value: String) : SqlValue()

    /** Bytes, such as a UUID's sixteen. */
    public class Bytes(value: ByteArray) : SqlValue() {
        private val bytes = value.copyOf()

        /** A copy of the bytes. */
        public val value: ByteArray get() = bytes.copyOf()

        /** How many bytes there are. */
        public val size: Int get() = bytes.size

        /** The byte at [index], without copying. */
        public operator fun get(index: Int): Byte = bytes[index]

        override fun equals(other: Any?): Boolean = other is Bytes && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Bytes(${bytes.size})"
    }
}

/**
 * A row read back from a query: its values by column name. Read with a [Column], which knows the
 * type: `row[Homes.name]`.
 */
public class Row(
    names: List<String>,
    /** The values, in the same order as [names]. */
    public val values: List<SqlValue>,
) {
    /** The columns' names, in the query's order, lowercase. */
    public val names: List<String> = names.map { it.lowercase() }

    init {
        require(names.size == values.size) { "a row has ${names.size} names and ${values.size} values" }
    }

    /** The value of the column called [name], or [SqlValue.Null] if the query didn't select it. */
    public fun value(name: String): SqlValue {
        val index = names.indexOf(name.lowercase())
        return if (index < 0) SqlValue.Null else values[index]
    }

    /** The column's value, as its type reads it. */
    public operator fun <T> get(column: Column<T>): T = column.type.decode(value(column.name))

    /** The value at [index] as a number, for `COUNT(*)` and `MAX(id)`; `null` for `NULL`. */
    public fun long(index: Int): Long? = when (val value = values[index]) {
        is SqlValue.Integer -> value.value
        is SqlValue.Real -> value.value.toLong()
        is SqlValue.Text -> value.value.toLongOrNull()
        else -> null
    }

    override fun toString(): String = names.zip(values).joinToString(prefix = "Row(", postfix = ")") { "${it.first}=${it.second}" }
}
