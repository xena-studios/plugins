package co.xenastudios.xplots.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
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
import co.xenastudios.xplots.store.Store.Companion.int
import co.xenastudios.xplots.store.Store.Companion.key
import co.xenastudios.xplots.store.Store.Companion.long
import co.xenastudios.xplots.store.Store.Companion.player
import co.xenastudios.xplots.store.Store.Companion.text

/** Reads every plot once, as xPlots starts: apart from [Store], whose writes load with the first change. */
public class StoreReader(private val db: Database, private val log: Log) {
    private val sql = db.sql

    /** Reads every field, plot, lease, member and renewal. On the database's threads. */
    public fun load(): Pending<Outcome<Stored, DbError>> = db.read { session -> read(session) }

    private fun read(session: SqlSession): Outcome<Stored, DbError> {
        val plots = Plots()
        val fields = HashMap<Int, Field>()
        val fieldRows = session.query(sql.raw("SELECT id, name, world, min_x, min_y, min_z, max_x, max_y, max_z, size_x, size_z, path, settings, warp FROM {p}field"))
        for (row in (fieldRows as? Outcome.Ok ?: return fieldRows as Outcome.Err).value) {
            val v = row.values
            val area = Box(int(v[3]), int(v[4]), int(v[5]), int(v[6]), int(v[7]), int(v[8]))
            val layout = Layout.of(area, int(v[9]), int(v[10]), int(v[11]))
            val name = text(v[1]) ?: continue
            if (layout == null) {
                log.warn("Field $name is stored with a plot size that doesn't fit it; skipped.")
                continue
            }
            val field = Field(int(v[0]), name, text(v[2]) ?: continue, layout)
            field.overrides = Settings.decode(text(v[12]))
            field.warp = text(v[13])
            fields[field.id] = field
        }
        val byKey = HashMap<Long, Plot>()
        val plotRows = session.query(sql.raw("SELECT field, number, min_x, min_y, min_z, max_x, max_y, max_z, sign_x, sign_y, sign_z, sign_facing FROM {p}plot"))
        for (row in (plotRows as? Outcome.Ok ?: return plotRows as Outcome.Err).value) {
            val v = row.values
            val field = fields[int(v[0])] ?: continue
            val plot = Plot(
                field, int(v[1]), Box(int(v[2]), int(v[3]), int(v[4]), int(v[5]), int(v[6]), int(v[7])),
                SignSpot(int(v[8]), int(v[9]), int(v[10]), Facing.of(text(v[11]))),
            )
            field.add(plot)
            byKey[key(field.id, plot.number)] = plot
        }
        val leases = HashMap<Plot, Lease>()
        val leaseRows = session.query(sql.raw("SELECT field, number, owner_most, owner_least, owner_name, expires, slots, warned FROM {p}lease"))
        for (row in (leaseRows as? Outcome.Ok ?: return leaseRows as Outcome.Err).value) {
            val v = row.values
            val plot = byKey[key(int(v[0]), int(v[1]))] ?: continue
            leases[plot] = Lease(player(v[2], v[3]), text(v[4]) ?: "?", long(v[5]), int(v[6]), int(v[7]), emptyMap())
        }
        val members = HashMap<Plot, LinkedHashMap<PlayerRef, String>>()
        val memberRows = session.query(sql.raw("SELECT field, number, uuid_most, uuid_least, name FROM {p}member"))
        for (row in (memberRows as? Outcome.Ok ?: return memberRows as Outcome.Err).value) {
            val v = row.values
            val plot = byKey[key(int(v[0]), int(v[1]))] ?: continue
            if (plot !in leases) continue
            members.getOrPut(plot) { LinkedHashMap() }[player(v[2], v[3])] = text(v[4]) ?: "?"
        }
        val renewals = HashMap<Plot, Renewal>()
        val renewalRows = session.query(sql.raw("SELECT field, number, auto, charge FROM {p}renewal"))
        if (renewalRows is Outcome.Ok) {
            for (row in renewalRows.value) {
                val v = row.values
                val plot = byKey[key(int(v[0]), int(v[1]))] ?: continue
                renewals[plot] = Renewal(int(v[2]) != 0, (v[3] as? SqlValue.Integer)?.value)
            }
        }
        for (field in fields.values) {
            for (plot in field.plots()) {
                val lease = leases[plot] ?: continue
                plot.lease = lease.with(members = members[plot] ?: emptyMap())
            }
            plots.index(field)
        }
        return Outcome.Ok(Stored(plots, renewals))
    }

}
