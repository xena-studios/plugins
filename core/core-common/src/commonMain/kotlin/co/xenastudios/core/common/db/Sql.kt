package co.xenastudios.core.common.db

/** One statement and the values bound to its `?`s, in order. */
public class Statement(public val sql: String, public val args: List<SqlValue> = emptyList()) {
    init {
        require(sql.count { it == '?' } == args.size) { "\"$sql\" has ${sql.count { it == '?' }} ?s and ${args.size} values" }
    }

    override fun equals(other: Any?): Boolean = other is Statement && other.sql == sql && other.args == args

    override fun hashCode(): Int = sql.hashCode() * 31 + args.hashCode()

    override fun toString(): String = if (args.isEmpty()) sql else "$sql $args"
}

/** The values of some of a table's columns, for an insert, an update or an upsert: `values { it[Homes.x] = 1.0 }`. */
public class Values internal constructor(public val table: Table) {
    private val set = LinkedHashMap<Column<*>, SqlValue>()

    /** The columns given so far, in the order they were. */
    public val columns: List<Column<*>> get() = set.keys.toList()

    /** Sets [column] to [value]. */
    public operator fun <T> set(column: Column<T>, value: T) {
        require(column.table === table) { "$column isn't in $table" }
        set[column] = column.type.encode(value)
    }

    /** The value given for [column], encoded. */
    public fun encoded(column: Column<*>): SqlValue = set[column] ?: throw IllegalArgumentException("no value for $column")
}

/** The values of [table]'s columns, set by [block]. */
public fun values(table: Table, block: (Values) -> Unit): Values = Values(table).also(block)

/** A `WHERE` condition: [sql] with `?`s and their [args]. Built from columns: `(Homes.owner eq id) and (Homes.name eq "base")`. */
public class Where internal constructor(public val sql: String, public val args: List<SqlValue>) {
    /** Both. */
    public infix fun and(other: Where): Where = Where("($sql) AND (${other.sql})", args + other.args)

    /** Either. */
    public infix fun or(other: Where): Where = Where("($sql) OR (${other.sql})", args + other.args)

    public companion object {
        /** Every row. */
        public val ALL: Where = Where("1=1", emptyList())

        /** A condition written by hand, for what the builders don't cover: `{p}` is the prefix, as in [Sql.raw]. */
        public fun raw(sql: String, vararg args: SqlValue): Where = Where(sql, args.toList())
    }
}

/** `column = value`, or `column IS NULL` for a null. */
public infix fun <T> Column<T>.eq(value: T): Where {
    val encoded = type.encode(value)
    return if (encoded == SqlValue.Null) Where("$name IS NULL", emptyList()) else Where("$name = ?", listOf(encoded))
}

/** `column < value`. */
public infix fun <T> Column<T>.lt(value: T): Where = Where("$name < ?", listOf(type.encode(value)))

/** `column <= value`. */
public infix fun <T> Column<T>.le(value: T): Where = Where("$name <= ?", listOf(type.encode(value)))

/** `column > value`. */
public infix fun <T> Column<T>.gt(value: T): Where = Where("$name > ?", listOf(type.encode(value)))

/** `column >= value`. */
public infix fun <T> Column<T>.ge(value: T): Where = Where("$name >= ?", listOf(type.encode(value)))

/** `column IN (...)`; never matches when [values] is empty. */
public infix fun <T> Column<T>.within(values: Collection<T>): Where =
    if (values.isEmpty()) Where("1=0", emptyList())
    else Where("$name IN (${values.joinToString(",") { "?" }})", values.map { type.encode(it) })

/** An `ORDER BY` column. */
public class Order internal constructor(internal val sql: String)

/** Smallest first. */
public val Column<*>.ascending: Order get() = Order("$name ASC")

/** Largest first. */
public val Column<*>.descending: Order get() = Order("$name DESC")

/**
 * Builds statements for one database ([dialect]) and one plugin's tables ([prefix]): every
 * table name gets the prefix, so plugins sharing the family's database never meet. Pure: it
 * runs nothing, so it's the same on every platform and tests read its output.
 */
public class Sql(public val dialect: Dialect, public val prefix: String) {
    init {
        require(PREFIX.matches(prefix)) { "\"$prefix\" isn't a table prefix: up to 32 lowercase letters, digits and _" }
    }

    /** [table]'s name in the database, prefix included. */
    public fun name(table: Table): String = prefix + table.tableName

    /**
     * Makes [table] and its indexes if they aren't there, so running it again does nothing. On
     * MySQL (whose `CREATE INDEX` has no `IF NOT EXISTS`) an index that's there fails with
     * [DbError.Kind.ALREADY_EXISTS], which [Migrations] skip.
     */
    public fun createTable(table: Table): List<Statement> {
        val columns = table.columns.map { column ->
            if (column.rowId) "${column.name} ${rowId()}" else "${column.name} ${column.type.declaration(dialect)}"
        }
        val key = if (table.primaryKey.isNotEmpty() && table.primaryKey.none { it.rowId }) {
            listOf("PRIMARY KEY (${joined(table.primaryKey, ", ")})")
        } else {
            emptyList()
        }
        val create = Statement("CREATE TABLE IF NOT EXISTS ${name(table)} (${(columns + key).joinToString(", ")})${tableOptions()}")
        return listOf(create) + table.indexes.map { createIndex(table, it) }
    }

    /** Makes one index of [table]'s; see [createTable]. */
    public fun createIndex(table: Table, index: Index): Statement {
        val indexName = name(table) + "_" + joined(index.columns, "_")
        val unique = if (index.unique) "UNIQUE " else ""
        val ifMissing = if (dialect is Dialect.Sqlite) "IF NOT EXISTS " else ""
        return Statement("CREATE ${unique}INDEX $ifMissing$indexName ON ${name(table)} (${joined(index.columns, ", ")})")
    }

    /** Adds [column] to its table, which already exists: a migration's step. Fails with [DbError.Kind.ALREADY_EXISTS] if it's there. */
    public fun addColumn(column: Column<*>): Statement {
        require(!column.rowId) { "a row id can't be added to a table that exists" }
        return Statement("ALTER TABLE ${name(column.table)} ADD COLUMN ${column.name} ${column.type.declaration(dialect)}")
    }

    /** `INSERT`. */
    public fun insert(values: Values): Statement = insertStatement("INSERT", values, "")

    /** `INSERT` that skips the row, without an error, when its key is already there. */
    public fun insertIgnore(values: Values): Statement = when (dialect) {
        is Dialect.Sqlite -> insertStatement("INSERT OR IGNORE", values, "")
        Dialect.MySql -> insertStatement("INSERT IGNORE", values, "")
    }

    /**
     * Inserts the row, or updates the one with the same [key] (the table's primary key by
     * default). One statement where the database has upserts; on SQLite before 3.24 (Spigot
     * 1.8.8's driver) two, an `UPDATE` then an `INSERT OR IGNORE`, which together do the same
     * inside the writer's transaction. Run the returned statements together, in order.
     */
    public fun upsert(values: Values, key: List<Column<*>> = values.table.primaryKey): List<Statement> {
        val table = values.table
        require(key.isNotEmpty()) { "an upsert into $table needs a key" }
        require(key.all { it in values.columns }) { "an upsert into $table needs a value for each of its key's columns" }
        val rest = values.columns.filter { it !in key }
        if (rest.isEmpty()) return listOf(insertIgnore(values))
        return when (dialect) {
            is Dialect.Sqlite -> if (dialect.nativeUpsert) {
                listOf(insertStatement("INSERT", values,
                    " ON CONFLICT (${joined(key, ", ")}) DO UPDATE SET " +
                        joined(rest, ", ", "# = excluded.#")))
            } else {
                val where = joined(key, " AND ", "# = ?")
                listOf(
                    Statement("UPDATE ${name(table)} SET ${joined(rest, ", ", "# = ?")} WHERE $where",
                        rest.map { values.encoded(it) } + key.map { values.encoded(it) }),
                    insertIgnore(values),
                )
            }
            Dialect.MySql -> listOf(insertStatement("INSERT", values,
                " ON DUPLICATE KEY UPDATE " + joined(rest, ", ", "# = VALUES(#)")))
        }
    }

    /** `UPDATE ... SET` the [values] `WHERE` [where]. */
    public fun update(values: Values, where: Where): Statement {
        require(values.columns.isNotEmpty()) { "an update sets something" }
        val set = joined(values.columns, ", ", "# = ?")
        return Statement("UPDATE ${name(values.table)} SET $set WHERE ${expand(where.sql)}", values.columns.map { values.encoded(it) } + where.args)
    }

    /** `DELETE FROM` [table] `WHERE` [where]. */
    public fun delete(table: Table, where: Where): Statement =
        Statement("DELETE FROM ${name(table)} WHERE ${expand(where.sql)}", where.args)

    /**
     * `SELECT` [columns] (all of them by default) of the rows that match [where], in [order], at
     * most [limit], skipping the first [offset] (which needs a [limit], as MySQL's does).
     */
    public fun select(
        table: Table,
        where: Where = Where.ALL,
        columns: List<Column<*>> = table.columns,
        order: List<Order> = emptyList(),
        limit: Int? = null,
        offset: Long = 0,
    ): Statement {
        require(offset == 0L || limit != null) { "an offset needs a limit" }
        val orderBy = if (order.isEmpty()) "" else " ORDER BY " + order.joinToString(", ") { it.sql }
        val limited = if (limit == null) "" else " LIMIT $limit" + if (offset > 0) " OFFSET $offset" else ""
        return Statement("SELECT ${joined(columns, ", ")} FROM ${name(table)} WHERE ${expand(where.sql)}$orderBy$limited", where.args)
    }

    /** `SELECT COUNT(*)` of the rows that match [where]: read it with `row.long(0)`. */
    public fun count(table: Table, where: Where = Where.ALL): Statement =
        Statement("SELECT COUNT(*) FROM ${name(table)} WHERE ${expand(where.sql)}", where.args)

    /**
     * A statement written by hand, for what the builders don't cover: `{p}` becomes the prefix,
     * so `SELECT ... FROM {p}homes` reads this plugin's table.
     */
    public fun raw(sql: String, vararg args: SqlValue): Statement = Statement(expand(sql), args.toList())

    private fun expand(sql: String) = sql.replace("{p}", prefix)

    // Each column in [pattern] (`#` for its name), joined: a loop rather than joinToString's
    // lambda, each of which the JVM makes a class of where it's first reached, as a plugin with a
    // database starts (decisions/2026-11-25-a-lighter-core.md).
    private fun joined(columns: List<Column<*>>, separator: String, pattern: String = "#"): String {
        val out = StringBuilder()
        for ((i, column) in columns.withIndex()) {
            if (i > 0) out.append(separator)
            out.append(pattern.replace("#", column.name))
        }
        return out.toString()
    }

    private fun insertStatement(verb: String, values: Values, tail: String): Statement {
        val columns = values.columns
        require(columns.isNotEmpty()) { "an insert into ${values.table} has values" }
        return Statement(
            "$verb INTO ${name(values.table)} (${joined(columns, ", ")}) VALUES (${joined(columns, ", ", "?")})$tail",
            columns.map { values.encoded(it) },
        )
    }

    // A plain INTEGER PRIMARY KEY is SQLite's own row id: no extra index, no AUTOINCREMENT table.
    private fun rowId() = when (dialect) {
        is Dialect.Sqlite -> "INTEGER PRIMARY KEY"
        Dialect.MySql -> "BIGINT PRIMARY KEY AUTO_INCREMENT"
    }

    // utf8mb4 whatever the database's default: an emoji in chat would otherwise fail every
    // write it's in.
    private fun tableOptions() = when (dialect) {
        is Dialect.Sqlite -> ""
        Dialect.MySql -> " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    }

    public companion object {
        private val PREFIX = Regex("[a-z0-9_]{0,32}")

        /** Whether [prefix] can be a table prefix, for checking a config value before it's used. */
        public fun validPrefix(prefix: String): Boolean = PREFIX.matches(prefix)
    }
}
