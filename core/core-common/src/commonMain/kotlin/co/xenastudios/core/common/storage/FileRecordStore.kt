package co.xenastudios.core.common.storage

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.CorruptValueException
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.async

/**
 * A [RecordStore] in one file of the plugin's folder, for Pumpkin, whose plugins have files but
 * no SQL (M0). Every record is in memory, read once when it opens, so reads answer at once;
 * changes are written to the file together, on the next async task after them, all or nothing
 * ([Storage.write]), and a change's [Pending] settles once the file holding it is written. If the
 * write fails, those changes are reported failed, stay in memory, and go with the next write.
 *
 * For the small sets a plugin keeps per server (homes, warps, settings): the whole file is
 * rewritten on each save. The format is text, one record a line, with the columns named in its
 * header, so a column added later reads as `NULL` from an old file.
 */
public class FileRecordStore<K, V> private constructor(
    private val storage: Storage,
    private val scheduler: Scheduler,
    private val path: StoragePath,
    private val type: RecordType<K, V>,
    private val log: Log,
    loaded: Map<SqlValue, Row>,
) : RecordStore<K, V> {
    private val lock = Any()
    private val rows = LinkedHashMap(loaded)
    private var waiting = ArrayList<Pending<Outcome<Unit, DbError>>>()
    private var scheduled = false
    private val names = type.table.columns.map { it.name }

    override fun get(key: K): Pending<Outcome<V?, DbError>> = pendingOf(read {
        locked(lock) { rows[type.key.type.encode(key)] }?.let { type.read(it) }
    })

    override fun all(): Pending<Outcome<List<V>, DbError>> = pendingOf(read {
        locked(lock) { rows.values.toList() }.map { type.read(it) }
    })

    override fun put(value: V): Pending<Outcome<Unit, DbError>> {
        val values = values(type.table) { type.write(value, it) }
        val row = Row(names, type.table.columns.map { values.encoded(it) })
        return change { rows[values.encoded(type.key)] = row }
    }

    override fun remove(key: K): Pending<Outcome<Unit, DbError>> = change { rows.remove(type.key.type.encode(key)) }

    private inline fun <T> read(block: () -> T): Outcome<T, DbError> = try {
        Outcome.Ok(block())
    } catch (e: CorruptValueException) {
        Outcome.Err(DbError(DbError.Kind.CORRUPT, "$path: ${e.message}"))
    }

    private fun change(apply: () -> Unit): Pending<Outcome<Unit, DbError>> {
        val done = newPending<Outcome<Unit, DbError>>()
        val schedule = locked(lock) {
            apply()
            waiting.add(done)
            (!scheduled).also { scheduled = true }
        }
        if (schedule) scheduler.async { flush() }
        return done
    }

    private fun flush() {
        val (text, settling) = locked(lock) {
            scheduled = false
            val settling = waiting
            waiting = ArrayList()
            RecordFile.write(names, rows.values) to settling
        }
        val result: Outcome<Unit, DbError> = when (val written = storage.writeText(path, text)) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> {
                log.warn("Couldn't save ${type.table.tableName} to $path (${written.error.detail}); the changes are kept in memory for the next save.")
                Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, written.error.detail))
            }
        }
        settling.forEach { it.resolve(result) }
    }

    public companion object {
        /**
         * Reads the file at [path] (none is an empty store). Run off the server's threads, as
         * [Storage] is; a file that can't be read or parsed is an error, and left as it is.
         */
        public fun <K, V> open(
            storage: Storage,
            scheduler: Scheduler,
            path: StoragePath,
            type: RecordType<K, V>,
            log: Log,
        ): Outcome<FileRecordStore<K, V>, DbError> {
            val text = when (val read = storage.readText(path)) {
                is Outcome.Ok -> read.value ?: ""
                is Outcome.Err -> return Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, "$path: ${read.error.detail}"))
            }
            val rows = when (val parsed = RecordFile.read(text)) {
                is Outcome.Ok -> parsed.value
                is Outcome.Err -> return Outcome.Err(DbError(DbError.Kind.CORRUPT, "$path: ${parsed.error}"))
            }
            val byKey = LinkedHashMap<SqlValue, Row>()
            for (row in rows) byKey[row.value(type.key.name)] = row
            return Outcome.Ok(FileRecordStore(storage, scheduler, path, type, log, byKey))
        }
    }
}

/**
 * The text a [FileRecordStore] keeps: a header naming the columns, then one line per row, its
 * values separated by tabs, each marked by kind: `~` for `NULL`, `i` a whole number, `r` a real,
 * `t` text with `\`, tab and line breaks escaped, `b` bytes in hex.
 */
internal object RecordFile {
    private const val MAGIC = "xena-records 1"

    fun write(names: List<String>, rows: Collection<Row>): String = buildString {
        append(MAGIC).append('\n')
        append(names.joinToString("\t")).append('\n')
        for (row in rows) {
            names.joinTo(this, "\t") { encode(row.value(it)) }
            append('\n')
        }
    }

    fun read(text: String): Outcome<List<Row>, String> {
        if (text.isEmpty()) return Outcome.Ok(emptyList())
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        if (lines.firstOrNull() != MAGIC || lines.size < 2) return Outcome.Err("not a record file (its first line isn't \"$MAGIC\")")
        val names = lines[1].split('\t')
        val rows = ArrayList<Row>(lines.size - 2)
        for (index in 2 until lines.size) {
            val fields = lines[index].split('\t')
            if (fields.size != names.size) return Outcome.Err("line ${index + 1} has ${fields.size} values for ${names.size} columns")
            val values = fields.map { decode(it) ?: return Outcome.Err("line ${index + 1} has a value that can't be read: \"$it\"") }
            rows += Row(names, values)
        }
        return Outcome.Ok(rows)
    }

    private fun encode(value: SqlValue): String = when (value) {
        SqlValue.Null -> "~"
        is SqlValue.Integer -> "i${value.value}"
        is SqlValue.Real -> "r${value.value}"
        is SqlValue.Text -> "t" + buildString {
            for (char in value.value) {
                when (char) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(char)
                }
            }
        }
        is SqlValue.Bytes -> "b" + value.value.toHexString()
    }

    private fun decode(field: String): SqlValue? {
        if (field == "~") return SqlValue.Null
        val body = field.drop(1)
        return when (field.firstOrNull()) {
            'i' -> body.toLongOrNull()?.let { SqlValue.Integer(it) }
            'r' -> body.toDoubleOrNull()?.let { SqlValue.Real(it) }
            'b' -> if (body.length % 2 == 0 && body.all { it in "0123456789abcdef" }) SqlValue.Bytes(body.hexToByteArray()) else null
            't' -> {
                val out = StringBuilder(body.length)
                var i = 0
                while (i < body.length) {
                    val char = body[i++]
                    if (char != '\\') {
                        out.append(char)
                        continue
                    }
                    out.append(when (body.getOrNull(i++)) {
                        '\\' -> '\\'
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> return null
                    })
                }
                SqlValue.Text(out.toString())
            }
            else -> null
        }
    }
}
