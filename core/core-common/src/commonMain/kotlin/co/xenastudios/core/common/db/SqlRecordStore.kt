package co.xenastudios.core.common.db

import co.xenastudios.core.api.Pending
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.storage.RecordStore
import co.xenastudios.core.common.storage.RecordType

/**
 * A [RecordStore] in [database]: one row per record in [type]'s table, which the plugin's
 * migrations make (`sql.createTable(type.table)`). Saves are upserts through the writer, so a
 * burst of them is one transaction; reads run beside it.
 */
public class SqlRecordStore<K, V>(private val database: Database, private val type: RecordType<K, V>) : RecordStore<K, V> {
    private val sql get() = database.sql

    override fun get(key: K): Pending<Outcome<V?, DbError>> = database.read { session ->
        decoding { session.queryOne(sql.select(type.table, type.key eq key, limit = 1)) { type.read(it) } }
    }

    override fun all(): Pending<Outcome<List<V>, DbError>> = database.read { session ->
        decoding {
            when (val rows = session.query(sql.select(type.table))) {
                is Outcome.Ok -> Outcome.Ok(rows.value.map { type.read(it) })
                is Outcome.Err -> rows
            }
        }
    }

    override fun put(value: V): Pending<Outcome<Unit, DbError>> =
        database.write(sql.upsert(values(type.table) { type.write(value, it) }))

    override fun remove(key: K): Pending<Outcome<Unit, DbError>> =
        database.write(sql.delete(type.table, type.key eq key))
}

/** [read]'s result, or [DbError.Kind.CORRUPT] if a stored value couldn't be read. */
internal inline fun <T> decoding(read: () -> Outcome<T, DbError>): Outcome<T, DbError> = try {
    read()
} catch (e: CorruptValueException) {
    Outcome.Err(DbError(DbError.Kind.CORRUPT, e.message ?: e.toString()))
}
