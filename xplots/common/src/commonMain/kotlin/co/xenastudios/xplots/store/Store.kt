package co.xenastudios.xplots.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.platform.Log
import co.xenastudios.xplots.model.Box
import co.xenastudios.xplots.model.Facing
import co.xenastudios.xplots.model.Field
import co.xenastudios.xplots.model.Layout
import co.xenastudios.xplots.model.Lease
import co.xenastudios.xplots.model.Plot
import co.xenastudios.xplots.model.Plots
import co.xenastudios.xplots.model.Settings
import co.xenastudios.xplots.model.SignSpot
import kotlin.uuid.Uuid

/** A rented plot's renewal: whether it renews on its own, and the charge being made now (the expiry it extends from), if any. */
public class Renewal(public val auto: Boolean, public val charge: Long?)

/** A renewal's notice for an owner who was away: [kind] is the message's key (`renewed`, `renew-failed`), told as they next join. */
public class Notice(public val plot: Plot, public val owner: PlayerRef, public val kind: String, public val price: Double)

/** What [Store.load] read: every field and plot, the renewals by plot, and the notices waiting for their owners. */
public class Stored(public val plots: Plots, public val renewals: Map<Plot, Renewal>, public val notices: List<Notice> = emptyList())

/**
 * Where plots survive a restart. Everything is read once as xPlots starts, off the server's
 * threads; after that xPlots works from memory and hands changes here, which the core's writer
 * writes on its own thread in the order they were made, retried while the database is away, so
 * the database never has a later change without an earlier one (1.x's rule). A click never waits
 * on the disk. Each change copies what it writes as it's made, so the writer never reads the live
 * model.
 */
public class Store(public val db: Database, private val log: Log) {
    private val sql = db.sql

    // ---- changes: each copies what it needs now, and writes it later ----

    /** A new field and all its plots. */
    public fun saveField(field: Field) {
        write(buildList {
            add(fieldInsert(field))
            field.plots().forEach { add(plotInsert(it)) }
        })
    }

    /** A field's settings or warp. */
    public fun updateField(field: Field) {
        val settings = Settings.encode(field.overrides).ifEmpty { null }
        write(listOf(sql.raw("UPDATE {p}field SET settings = ?, warp = ? WHERE id = ?", nullable(settings), nullable(field.warp), SqlValue.Integer(field.id.toLong()))))
    }

    public fun deleteField(field: Field) {
        val id = SqlValue.Integer(field.id.toLong())
        write(TABLES.map { sql.raw("DELETE FROM {p}$it WHERE field = ?", id) } + sql.raw("DELETE FROM {p}field WHERE id = ?", id))
    }

    /** A plot's row, new or changed (its sign moved). */
    public fun savePlot(plot: Plot) {
        write(listOf(deletePlotRow("plot", plot), plotInsert(plot)))
    }

    public fun deletePlot(plot: Plot) {
        write(TABLES.map { deletePlotRow(it, plot) })
    }

    /** A plot's lease and members as they are now, or its lack of one (which takes its renewal too). */
    public fun saveLease(plot: Plot): Pending<Outcome<Unit, DbError>> = write(leaseStatements(plot, plot.lease))

    /** The lease as [lease] says, its renewal as [renewal] says, in one transaction: a renewal's charge settled. */
    public fun saveLease(plot: Plot, lease: Lease?, renewal: Renewal?): Pending<Outcome<Unit, DbError>> =
        write(leaseStatements(plot, lease) + renewalStatements(plot, renewal))

    /** A plot's renewal: `null` to take it away. */
    public fun saveRenewal(plot: Plot, renewal: Renewal?): Pending<Outcome<Unit, DbError>> = write(renewalStatements(plot, renewal))

    /** A notice kept for its owner, in place of any earlier one about the same plot. */
    public fun saveNotice(notice: Notice): Pending<Outcome<Unit, DbError>> {
        val (most, least) = longs(notice.owner)
        val plot = notice.plot
        val owner = arrayOf(fieldOf(plot), numberOf(plot), SqlValue.Integer(most), SqlValue.Integer(least))
        return write(listOf(
            sql.raw("DELETE FROM {p}notice WHERE field = ? AND number = ? AND owner_most = ? AND owner_least = ?", *owner),
            sql.raw("INSERT INTO {p}notice (field, number, owner_most, owner_least, kind, price) VALUES (?, ?, ?, ?, ?, ?)",
                *owner, SqlValue.Text(notice.kind), SqlValue.Real(notice.price)),
        ))
    }

    /** Every notice kept for [owner]: they've been told. */
    public fun deleteNotices(owner: PlayerRef): Pending<Outcome<Unit, DbError>> {
        val (most, least) = longs(owner)
        return write(listOf(sql.raw("DELETE FROM {p}notice WHERE owner_most = ? AND owner_least = ?", SqlValue.Integer(most), SqlValue.Integer(least))))
    }

    public fun saveSnapshot(plot: Plot, data: ByteArray): Pending<Outcome<Unit, DbError>> =
        write(listOf(deletePlotRow("snapshot", plot), sql.raw("INSERT INTO {p}snapshot (field, number, data) VALUES (?, ?, ?)", fieldOf(plot), numberOf(plot), SqlValue.Bytes(data))))

    /** The plot's snapshot, read after every change made before this call; `null` if it has none. */
    public fun loadSnapshot(plot: Plot): Pending<Outcome<ByteArray?, DbError>> {
        val statement = sql.raw("SELECT data FROM {p}snapshot WHERE field = ? AND number = ?", fieldOf(plot), numberOf(plot))
        return db.session { session ->
            when (val rows = session.query(statement)) {
                is Outcome.Ok -> Outcome.Ok((rows.value.firstOrNull()?.values?.get(0) as? SqlValue.Bytes)?.value)
                is Outcome.Err -> rows
            }
        }
    }

    private fun leaseStatements(plot: Plot, lease: Lease?): List<Statement> = buildList {
        add(deletePlotRow("member", plot))
        add(deletePlotRow("lease", plot))
        if (lease == null) {
            add(deletePlotRow("renewal", plot))
            return@buildList
        }
        val (most, least) = longs(lease.owner)
        add(sql.raw(
            "INSERT INTO {p}lease (field, number, owner_most, owner_least, owner_name, expires, slots, warned) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            fieldOf(plot), numberOf(plot), SqlValue.Integer(most), SqlValue.Integer(least), SqlValue.Text(lease.ownerName.take(16)),
            SqlValue.Integer(lease.expires), SqlValue.Integer(lease.slots.toLong()), SqlValue.Integer(lease.warned.toLong()),
        ))
        for ((member, name) in lease.members) {
            val (m, l) = longs(member)
            add(sql.raw(
                "INSERT INTO {p}member (field, number, uuid_most, uuid_least, name) VALUES (?, ?, ?, ?, ?)",
                fieldOf(plot), numberOf(plot), SqlValue.Integer(m), SqlValue.Integer(l), SqlValue.Text(name.take(16)),
            ))
        }
    }

    private fun renewalStatements(plot: Plot, renewal: Renewal?): List<Statement> = buildList {
        add(deletePlotRow("renewal", plot))
        if (renewal != null) {
            add(sql.raw(
                "INSERT INTO {p}renewal (field, number, auto, charge) VALUES (?, ?, ?, ?)",
                fieldOf(plot), numberOf(plot), SqlValue.Integer(if (renewal.auto) 1 else 0), renewal.charge?.let { SqlValue.Integer(it) } ?: SqlValue.Null,
            ))
        }
    }

    private fun write(statements: List<Statement>): Pending<Outcome<Unit, DbError>> {
        val pending = db.write(statements)
        pending.onSettled(Logged(log))
        return pending
    }

    private class Logged(private val log: Log) : (Outcome<Unit, DbError>?, Throwable?) -> Unit {
        override fun invoke(result: Outcome<Unit, DbError>?, error: Throwable?) {
            if (result is Outcome.Err && result.error.kind != DbError.Kind.CLOSED) log.error("Couldn't save a change to plots: ${result.error}")
            else if (error != null) log.error("Couldn't save a change to plots: $error")
        }
    }

    private fun fieldInsert(field: Field): Statement {
        val a = field.layout.area
        val settings = Settings.encode(field.overrides).ifEmpty { null }
        return sql.raw(
            "INSERT INTO {p}field (id, name, world, min_x, min_y, min_z, max_x, max_y, max_z, size_x, size_z, path, settings, warp)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            SqlValue.Integer(field.id.toLong()), SqlValue.Text(field.name), SqlValue.Text(field.world),
            i(a.minX), i(a.minY), i(a.minZ), i(a.maxX), i(a.maxY), i(a.maxZ),
            i(field.layout.sizeX), i(field.layout.sizeZ), i(field.layout.path), nullable(settings), nullable(field.warp),
        )
    }

    private fun plotInsert(plot: Plot): Statement {
        val b = plot.box
        val s = plot.sign
        return sql.raw(
            "INSERT INTO {p}plot (field, number, min_x, min_y, min_z, max_x, max_y, max_z, sign_x, sign_y, sign_z, sign_facing)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            fieldOf(plot), numberOf(plot), i(b.minX), i(b.minY), i(b.minZ), i(b.maxX), i(b.maxY), i(b.maxZ),
            i(s.x), i(s.y), i(s.z), SqlValue.Text(Facing.name(s.facing)),
        )
    }

    private fun deletePlotRow(table: String, plot: Plot): Statement =
        sql.raw("DELETE FROM {p}$table WHERE field = ? AND number = ?", fieldOf(plot), numberOf(plot))

    internal companion object {
        val TABLES = listOf("member", "lease", "snapshot", "renewal", "notice", "plot")

        fun i(value: Int): SqlValue = SqlValue.Integer(value.toLong())
        fun fieldOf(plot: Plot): SqlValue = SqlValue.Integer(plot.field.id.toLong())
        fun numberOf(plot: Plot): SqlValue = SqlValue.Integer(plot.number.toLong())
        fun nullable(text: String?): SqlValue = text?.let { SqlValue.Text(it) } ?: SqlValue.Null

        fun int(value: SqlValue): Int = long(value).toInt()

        fun long(value: SqlValue): Long = when (value) {
            is SqlValue.Integer -> value.value
            is SqlValue.Real -> value.value.toLong()
            is SqlValue.Text -> value.value.toLongOrNull() ?: 0L
            else -> 0L
        }

        fun text(value: SqlValue): String? = when (value) {
            is SqlValue.Text -> value.value
            is SqlValue.Null -> null
            is SqlValue.Integer -> value.value.toString()
            else -> null
        }

        fun player(most: SqlValue, least: SqlValue): PlayerRef = PlayerRef.of(Uuid.fromLongs(long(most), long(least)))

        fun longs(player: PlayerRef): Pair<Long, Long> {
            var most = 0L
            var least = 0L
            player.uuid.toLongs { m, l ->
                most = m
                least = l
            }
            return most to least
        }

        fun key(field: Int, number: Int): Long = (field.toLong() shl 32) or (number.toLong() and 0xFFFFFFFFL)
    }
}
