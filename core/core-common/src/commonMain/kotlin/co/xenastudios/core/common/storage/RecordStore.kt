package co.xenastudios.core.common.storage

import co.xenastudios.core.api.Pending
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Column
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.Values

/**
 * A plugin's records of one kind (homes, warps, balances) by key, wherever the platform keeps
 * them: [co.xenastudios.core.common.db.SqlRecordStore] in the plugin's database on the JVM,
 * [FileRecordStore] in a file of its folder on Pumpkin, which has no SQL (M0). A plugin that runs
 * on both writes its logic against this, and picks one at startup by
 * [co.xenastudios.core.common.platform.Capability.SQL].
 *
 * What it can do is what both can do well: one record by key, all of them, a change to one.
 * A plugin whose data needs queries (history, leaderboards) uses the database directly, and
 * isn't on Pumpkin.
 *
 * Every [Pending] settles with an [Outcome], never a failure, and a change's settles once it's
 * durable: committed, or written to the file.
 */
public interface RecordStore<K, V> {
    /** The record with [key], or `null`. */
    public fun get(key: K): Pending<Outcome<V?, DbError>>

    /** Every record. */
    public fun all(): Pending<Outcome<List<V>, DbError>>

    /** Saves [value], replacing the record with the same key. */
    public fun put(value: V): Pending<Outcome<Unit, DbError>>

    /** Deletes the record with [key], if there is one. */
    public fun remove(key: K): Pending<Outcome<Unit, DbError>>
}

/**
 * How records of [V] are kept: in [table]'s columns, by its one-column [key]. The same
 * declaration serves the database and the file.
 *
 * ```kotlin
 * object HomeRecords : RecordType<String, Home>(Homes, Homes.name) {
 *     override fun keyOf(value: Home) = value.name
 *     override fun write(value: Home, values: Values) { values[Homes.name] = value.name; values[Homes.x] = value.x }
 *     override fun read(row: Row) = Home(row[Homes.name], row[Homes.x])
 * }
 * ```
 */
public abstract class RecordType<K, V>(public val table: Table, public val key: Column<K>) {
    init {
        require(table.primaryKey == listOf(key)) { "a record type's key is its table's primary key" }
    }

    /** [value]'s key. */
    public abstract fun keyOf(value: V): K

    /** Sets every column of [value], its key included. */
    public abstract fun write(value: V, values: Values)

    /** The record in [row]. Throws [co.xenastudios.core.common.db.CorruptValueException] if a column can't be read. */
    public abstract fun read(row: Row): V
}
