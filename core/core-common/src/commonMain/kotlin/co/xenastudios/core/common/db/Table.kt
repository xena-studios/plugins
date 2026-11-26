package co.xenastudios.core.common.db

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * How values of [T] are stored: what a column of it is declared as on each database, and how a
 * value becomes a [SqlValue] and back. The core's types cover what plugins keep; a plugin can
 * make its own from one of them with [map].
 *
 * The built-in types are told apart by a number, not by a pair of lambdas each: the JVM makes a
 * class of every lambda, about 1.5 KB of metaspace, and every plugin loads these types with the
 * family's player cache (decisions/2026-11-25-a-lighter-core.md).
 */
public class ColumnType<T> private constructor(
    /** For errors: `uuid`, `text(32)`. */
    public val name: String,
    private val sqlite: String,
    private val mysql: String,
    private val kind: Int,
    /** Whether it allows `NULL` ([nullable]). */
    public val optional: Boolean,
    // NULLABLE and MAPPED: the type underneath, and MAPPED's two conversions.
    private val base: ColumnType<Any?>? = null,
    private val to: ((Any?) -> Any?)? = null,
    private val from: ((Any?) -> Any?)? = null,
) {
    /** What a column of this type is declared as on [dialect]. */
    public fun declaration(dialect: Dialect): String = when (dialect) {
        is Dialect.Sqlite -> sqlite
        Dialect.MySql -> mysql
    } + if (optional) "" else " NOT NULL"

    /** [value] as it's bound to a statement. */
    public fun encode(value: T): SqlValue = when (kind) {
        K_INT -> SqlValue.Integer((value as Int).toLong())
        K_LONG -> SqlValue.Integer(value as Long)
        K_DOUBLE -> SqlValue.Real(value as Double)
        K_BOOLEAN -> SqlValue.Integer(if (value as Boolean) 1 else 0)
        K_UUID -> SqlValue.Bytes((value as Uuid).toByteArray())
        K_INSTANT -> SqlValue.Integer((value as Instant).toEpochMilliseconds())
        K_BYTES -> SqlValue.Bytes(value as ByteArray)
        K_TEXT -> SqlValue.Text(value as String)
        K_NULLABLE -> if (value == null) SqlValue.Null else base!!.encode(value)
        else -> base!!.encode(from!!(value))
    }

    /** A stored value as a [T]. Throws [CorruptValueException] if it can't be one. */
    @Suppress("UNCHECKED_CAST")
    public fun decode(value: SqlValue): T = when (kind) {
        K_INT -> integer(value, "int").toInt()
        K_LONG -> integer(value, "long")
        K_DOUBLE -> when (value) {
            is SqlValue.Real -> value.value
            is SqlValue.Integer -> value.value.toDouble()
            else -> corrupt(value, "double")
        }
        K_BOOLEAN -> integer(value, "boolean") != 0L
        K_UUID -> if (value is SqlValue.Bytes && value.size == Uuid.SIZE_BYTES) Uuid.fromByteArray(value.value) else corrupt(value, "uuid")
        K_INSTANT -> Instant.fromEpochMilliseconds(integer(value, "long"))
        K_BYTES -> if (value is SqlValue.Bytes) value.value else corrupt(value, "bytes")
        K_TEXT -> if (value is SqlValue.Text) value.value else corrupt(value, "text")
        K_NULLABLE -> if (value == SqlValue.Null) null else base!!.decode(value)
        else -> to!!(base!!.decode(value))
    } as T

    /** The same type, allowing `NULL`: a column of it is declared without `NOT NULL`. */
    @Suppress("UNCHECKED_CAST")
    public fun nullable(): ColumnType<T?> =
        ColumnType("$name?", sqlite, mysql, K_NULLABLE, optional = true, base = this as ColumnType<Any?>)

    /** A type of the plugin's own, stored as this one: an enum by name, a duration in milliseconds. */
    @Suppress("UNCHECKED_CAST")
    public fun <R> map(name: String, to: (T) -> R, from: (R) -> T): ColumnType<R> =
        ColumnType(name, sqlite, mysql, K_MAPPED, optional, this as ColumnType<Any?>, to as (Any?) -> Any?, from as (Any?) -> Any?)

    override fun toString(): String = name

    public companion object {
        private const val K_INT = 0
        private const val K_LONG = 1
        private const val K_DOUBLE = 2
        private const val K_BOOLEAN = 3
        private const val K_UUID = 4
        private const val K_INSTANT = 5
        private const val K_BYTES = 6
        private const val K_TEXT = 7
        private const val K_NULLABLE = 8
        private const val K_MAPPED = 9

        /** A 32-bit number. */
        public val INT: ColumnType<Int> = ColumnType("int", "INTEGER", "INT", K_INT, optional = false)

        /** A 64-bit number. */
        public val LONG: ColumnType<Long> = ColumnType("long", "INTEGER", "BIGINT", K_LONG, optional = false)

        /** A floating-point number, such as a balance or a coordinate. */
        public val DOUBLE: ColumnType<Double> = ColumnType("double", "REAL", "DOUBLE", K_DOUBLE, optional = false)

        /** `true` or `false`, stored as 1 and 0. */
        public val BOOLEAN: ColumnType<Boolean> = ColumnType("boolean", "INTEGER", "TINYINT", K_BOOLEAN, optional = false)

        /** A UUID, as its 16 bytes: a player, a world. */
        public val UUID: ColumnType<Uuid> = ColumnType("uuid", "BLOB", "BINARY(16)", K_UUID, optional = false)

        /** A moment, as milliseconds since 1970. */
        public val INSTANT: ColumnType<Instant> = ColumnType("instant", "INTEGER", "BIGINT", K_INSTANT, optional = false)

        /** Bytes, up to 16 MiB on MySQL. Can't be part of a key there. */
        public val BYTES: ColumnType<ByteArray> = ColumnType("bytes", "BLOB", "MEDIUMBLOB", K_BYTES, optional = false)

        /**
         * Text of at most [max] characters: `VARCHAR(max)` on MySQL, in utf8mb4, so an emoji
         * fits. MySQL keys hold up to 768 such characters; past 16,383 it's `MEDIUMTEXT`, which
         * can't be a key. SQLite doesn't limit it.
         */
        public fun text(max: Int): ColumnType<String> {
            require(max in 1..4_000_000) { "text($max): a length is 1 to 4,000,000" }
            return ColumnType("text($max)", "TEXT", if (max <= 16_383) "VARCHAR($max)" else "MEDIUMTEXT", K_TEXT, optional = false)
        }

        private fun integer(value: SqlValue, type: String): Long = when (value) {
            is SqlValue.Integer -> value.value
            // MySQL's driver hands back DECIMAL and some aggregates as text.
            is SqlValue.Text -> value.value.toLongOrNull() ?: corrupt(value, type)
            else -> corrupt(value, type)
        }

        private fun corrupt(value: SqlValue, type: String): Nothing =
            throw CorruptValueException("expected a $type, found $value")
    }
}

/** A stored value its column's type can't read: the database was changed by hand, or by another program. */
public class CorruptValueException(message: String) : RuntimeException(message)

/** A column of a [Table], typed: `row[Homes.name]` is a `String`. */
public class Column<T> internal constructor(
    /** The table it's in. */
    public val table: Table,
    /** Its name, lowercase. */
    public val name: String,
    /** How its values are stored. */
    public val type: ColumnType<T>,
    /** Whether it's a key the database numbers itself ([Table.rowId]). */
    public val rowId: Boolean,
) {
    override fun toString(): String = "${table.tableName}.$name"
}

/** An index on some of a table's columns. Its name is the table's prefixed name, then the columns'. */
public class Index internal constructor(public val columns: List<Column<*>>, public val unique: Boolean)

/**
 * A table, declared once as an object, the same on every database:
 *
 * ```kotlin
 * object Homes : Table("homes") {
 *     val owner = column("owner", ColumnType.UUID)
 *     val name = column("name", ColumnType.text(32))
 *     val x = column("x", ColumnType.DOUBLE)
 *     init { primaryKey(owner, name) }
 * }
 * ```
 *
 * Its [tableName] doesn't include the plugin's prefix: [Sql] adds that, so one declaration serves a
 * plugin's own database and the family's shared one.
 */
public abstract class Table(
    /** Its name, without the plugin's prefix. Not `name`, so a table can have a column called that. */
    public val tableName: String,
) {
    private val declared = ArrayList<Column<*>>()
    private val declaredIndexes = ArrayList<Index>()
    private var key: List<Column<*>> = emptyList()

    init {
        require(NAME.matches(tableName)) { "\"$tableName\" isn't a table name: lowercase letters, digits and _, starting with a letter" }
    }

    /** Its columns, in the order they're declared. */
    public val columns: List<Column<*>> get() = declared

    /** The columns of its primary key; empty if it has none. */
    public val primaryKey: List<Column<*>> get() = key

    /** Its indexes, besides the primary key. */
    public val indexes: List<Index> get() = declaredIndexes

    /** Declares a column. */
    protected fun <T> column(name: String, type: ColumnType<T>): Column<T> = add(Column(this, name, type, rowId = false))

    /**
     * Declares the table's key as a number the database hands out on insert: SQLite's own row id
     * (`INTEGER PRIMARY KEY`, no extra index), `BIGINT AUTO_INCREMENT` on MySQL.
     */
    protected fun rowId(name: String = "id"): Column<Long> {
        check(key.isEmpty()) { "$tableName already has a primary key" }
        val column = add(Column(this, name, ColumnType.LONG, rowId = true))
        key = listOf(column)
        return column
    }

    /** Declares the primary key. */
    protected fun primaryKey(vararg columns: Column<*>) {
        check(key.isEmpty()) { "$tableName already has a primary key" }
        require(columns.isNotEmpty() && columns.all { it.table === this }) { "a key is some of $tableName's own columns" }
        key = columns.toList()
    }

    /** Declares an index. */
    protected fun index(vararg columns: Column<*>, unique: Boolean = false) {
        require(columns.isNotEmpty() && columns.all { it.table === this }) { "an index is on some of $tableName's own columns" }
        declaredIndexes += Index(columns.toList(), unique)
    }

    private fun <T> add(column: Column<T>): Column<T> {
        require(NAME.matches(column.name)) { "\"${column.name}\" isn't a column name: lowercase letters, digits and _" }
        require(declared.none { it.name == column.name }) { "$tableName has two columns called ${column.name}" }
        declared += column
        return column
    }

    override fun toString(): String = tableName

    internal companion object {
        val NAME = Regex("[a-z][a-z0-9_]{0,47}")
    }
}
