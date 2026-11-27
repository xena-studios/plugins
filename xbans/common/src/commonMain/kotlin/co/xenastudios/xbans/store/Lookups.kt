package co.xenastudios.xbans.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.Statement
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.store.Backend.Account
import co.xenastudios.xbans.store.Backend.Companion.first
import co.xenastudios.xbans.store.Backend.Companion.REPLACED_REASON
import co.xenastudios.xbans.store.Backend.Companion.long
import co.xenastudios.xbans.store.Backend.Companion.text
import co.xenastudios.xbans.store.Backend.Filter
import co.xenastudios.xbans.store.Backend.IpUse
import co.xenastudios.xbans.store.Backend.Known

// [Backend]'s lookups for commands (history, alts, pruning, forgetting), apart from what runs as
// the server starts, so their code loads with the first command that asks rather than at startup.

/**
 * Deletes [target]'s punishments that are no longer in force at [now] (ended, revoked, kicks),
 * made at or after [since]: `/prunehistory`. With [dryRun], only counts them. How many.
 */
public fun Backend.prune(target: PlayerRef, since: Long, now: Long, dryRun: Boolean): Pending<Outcome<Int, DbError>> {
    val work = { session: SqlSession ->
        when (val found = rows(session, raw(Tables.SELECT + " WHERE p.uuid = ? AND p.created >= ?", text(target.toString()), long(since)))) {
            is Outcome.Err -> found
            is Outcome.Ok -> {
                val ids = found.value.filter { !it.active(now) }.map { it.id }
                if (dryRun || ids.isEmpty()) {
                    Outcome.Ok(ids.size)
                } else {
                    var failed: Outcome.Err<DbError>? = null
                    for (id in ids) {
                        for (table in listOf("{p}removals WHERE punishment", "{p}punishments WHERE id")) {
                            val done = session.execute(raw("DELETE FROM $table = ?", long(id)))
                            if (done is Outcome.Err) failed = done
                        }
                    }
                    failed ?: Outcome.Ok(ids.size)
                }
            }
        }
    }
    return if (dryRun) database.read(work) else database.transaction(work)
}

/**
 * `/xbans forget`: what xBans keeps about [player] beyond their punishments, the IPs they used
 * and their name, weight and exemption when last seen. Their punishments stay
 * (decisions/2026-11-26-xbans-2.md). How many rows went.
 */
public fun Backend.forget(player: PlayerRef): Pending<Outcome<Int, DbError>> = database.transaction { session ->
    var count = 0
    for (table in listOf("{p}ips", "{p}players")) {
        when (val done = session.execute(raw("DELETE FROM $table WHERE uuid = ?", text(player.toString())))) {
            is Outcome.Err -> return@transaction done
            is Outcome.Ok -> count += done.value
        }
    }
    Outcome.Ok(count)
}

// ---- reads ----

/** One punishment with its removal, or `null`. */
public fun Backend.find(id: Long): Pending<Outcome<Entry?, DbError>> = database.read { session -> find(session, id) }

public fun Backend.find(session: SqlSession, id: Long): Outcome<Entry?, DbError> =
    when (val found = rows(session, raw(Tables.SELECT + " WHERE p.id = ?", long(id)))) {
        is Outcome.Ok -> Outcome.Ok(found.value.firstOrNull())
        is Outcome.Err -> found
    }

/** [target]'s warnings in force at [now], newest first. */
public fun Backend.activeWarnings(target: PlayerRef, now: Long): Pending<Outcome<List<Entry>, DbError>> = database.read { activeWarnings(it, target, now) }

internal fun Backend.activeWarnings(session: SqlSession, target: PlayerRef, now: Long): Outcome<List<Entry>, DbError> = rows(session, raw(
    Tables.SELECT + " WHERE p.uuid = ? AND p.type = 3 AND r.punishment IS NULL AND (p.expires = 0 OR p.expires > ?) ORDER BY p.id DESC",
    text(target.toString()), long(now),
))

/**
 * A player's or an IP's bans or mutes in force, from the database rather than memory, so a
 * server that missed a change can still revoke what's there: by account, and IP punishments on
 * the IP key. Newest first.
 */
public fun Backend.inForceOf(type: PunishmentType, target: PlayerRef?, ip: String?, now: Long): Pending<Outcome<List<Entry>, DbError>> =
    database.read { session ->
        val found = LinkedHashMap<Long, Entry>()
        val where = " WHERE p.type = ? AND r.punishment IS NULL AND (p.expires = 0 OR p.expires > ?) AND "
        if (target != null) {
            when (val rows = rows(session, raw(Tables.SELECT + where + "p.uuid = ?", Rows.int(Rows.code(type)), long(now), text(target.toString())))) {
                is Outcome.Err -> return@read rows
                is Outcome.Ok -> rows.value.forEach { found[it.id] = it }
            }
        }
        if (ip != null) {
            when (val rows = rows(session, raw(Tables.SELECT + where + "p.ip = ? AND p.ip_based = 1", Rows.int(Rows.code(type)), long(now), text(ip)))) {
                is Outcome.Err -> return@read rows
                is Outcome.Ok -> rows.value.forEach { found[it.id] = it }
            }
        }
        Outcome.Ok(found.values.sortedByDescending { it.id })
    }

/** Every account seen on an IP key, for holding an IP punishment to their weights and exemptions. */
public fun Backend.accountsOn(ip: String): Pending<Outcome<List<Account>, DbError>> = database.read { session ->
    when (val found = session.query(raw("SELECT p.uuid AS uuid, p.name AS name, p.weight AS weight, p.exempt AS exempt FROM {p}ips i JOIN {p}players p ON p.uuid = i.uuid WHERE i.ip = ?", text(ip)))) {
        is Outcome.Err -> found
        is Outcome.Ok -> Outcome.Ok(found.value.mapNotNull { row ->
            Rows.uuid(Rows.text(row, "uuid"))?.let { Account(it, Rows.text(row, "name") ?: "?", (Rows.long(row, "weight") ?: 0).toInt(), (Rows.long(row, "exempt") ?: 0) != 0L) }
        })
    }
}

/**
 * How many of [target]'s punishments under [template] count toward its ladder: those made since
 * [since] that weren't revoked (one replaced by a newer one still counts).
 */
public fun Backend.ladderStep(target: PlayerRef, template: String, since: Long): Pending<Outcome<Int, DbError>> = database.read { session ->
    when (val found = session.query(raw(
        "SELECT COUNT(*) FROM {p}punishments p LEFT JOIN {p}removals r ON r.punishment = p.id" +
            " WHERE p.uuid = ? AND p.template = ? AND p.created >= ? AND (r.punishment IS NULL OR r.reason = ?)",
        text(target.toString()), text(template), long(since), text(REPLACED_REASON),
    ))) {
        is Outcome.Err -> found
        is Outcome.Ok -> Outcome.Ok((found.value.firstOrNull()?.long(0) ?: 0).toInt())
    }
}

public fun Backend.known(id: PlayerRef): Pending<Outcome<Known, DbError>> = database.read { session ->
    val player = when (val rows = session.query(raw("SELECT name, weight, exempt FROM {p}players WHERE uuid = ?", text(id.toString())))) {
        is Outcome.Err -> return@read rows
        is Outcome.Ok -> rows.value.firstOrNull()
    }
    val ip = when (val rows = session.query(raw("SELECT ip FROM {p}ips WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1", text(id.toString())))) {
        is Outcome.Err -> return@read rows
        is Outcome.Ok -> rows.value.firstOrNull()?.let { Rows.text(it, "ip") }
    }
    Outcome.Ok(Known(player?.let { Rows.text(it, "name") }, player?.let { Rows.long(it, "weight") }?.toInt() ?: 0, (player?.let { Rows.long(it, "exempt") } ?: 0) != 0L, ip))
}

/** A player who joined, by name, newest first: for names the server doesn't know any more. */
public fun Backend.idByName(name: String): Pending<Outcome<Pair<PlayerRef, String>?, DbError>> = database.read { session ->
    when (val rows = session.query(raw("SELECT uuid, name FROM {p}players WHERE name = ? ORDER BY last_seen DESC LIMIT 1", text(name)))) {
        is Outcome.Err -> rows
        is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.let { row -> Rows.uuid(Rows.text(row, "uuid"))?.let { it to (Rows.text(row, "name") ?: name) } })
    }
}

/** The IPs an account has used, newest first. */
public fun Backend.ipHistory(id: PlayerRef): Pending<Outcome<List<IpUse>, DbError>> = database.read { session ->
    uses(session, raw(
        "SELECT i.uuid AS uuid, p.name AS name, i.ip AS ip, i.first_seen AS first_seen, i.last_seen AS last_seen FROM {p}ips i" +
            " LEFT JOIN {p}players p ON p.uuid = i.uuid WHERE i.uuid = ? ORDER BY i.last_seen DESC",
        text(id.toString()),
    ))
}

/** Other accounts that used any of [id]'s IPs (or, with no [id], this [ip] key), the most recent use of each first. */
public fun Backend.alts(id: PlayerRef?, ip: String?): Pending<Outcome<List<IpUse>, DbError>> = database.read { session ->
    val found = if (id != null) {
        uses(session, raw(
            "SELECT o.uuid AS uuid, p.name AS name, o.ip AS ip, o.first_seen AS first_seen, o.last_seen AS last_seen FROM {p}ips i" +
                " JOIN {p}ips o ON o.ip = i.ip AND o.uuid <> i.uuid LEFT JOIN {p}players p ON p.uuid = o.uuid" +
                " WHERE i.uuid = ? ORDER BY o.last_seen DESC",
            text(id.toString()),
        ))
    } else {
        uses(session, raw(
            "SELECT o.uuid AS uuid, p.name AS name, o.ip AS ip, o.first_seen AS first_seen, o.last_seen AS last_seen FROM {p}ips o" +
                " LEFT JOIN {p}players p ON p.uuid = o.uuid WHERE o.ip = ? ORDER BY o.last_seen DESC",
            text(ip ?: ""),
        ))
    }
    when (found) {
        is Outcome.Err -> found
        is Outcome.Ok -> {
            val seen = HashSet<PlayerRef>()
            Outcome.Ok(found.value.filter { it.id != null && seen.add(it.id) })
        }
    }
}

private fun Backend.uses(session: SqlSession, statement: Statement): Outcome<List<IpUse>, DbError> = when (val rows = session.query(statement)) {
    is Outcome.Err -> rows
    is Outcome.Ok -> Outcome.Ok(rows.value.map { row ->
        val id = Rows.uuid(Rows.text(row, "uuid"))
        IpUse(id, Rows.text(row, "name") ?: id.toString(), Rows.text(row, "ip") ?: "?", Rows.long(row, "first_seen") ?: 0, Rows.long(row, "last_seen") ?: 0)
    })
}

/** Up to [limit] punishments newer-first with ids below [before]: one page by key range, as fast deep in a long history as at its start. */
public fun Backend.page(session: SqlSession, filter: Filter, before: Long, limit: Int): Outcome<List<Entry>, DbError> =
    rows(session, raw(Tables.SELECT + filter.where + " AND p.id < ? ORDER BY p.id DESC LIMIT $limit", *(filter.args() + long(before)).toTypedArray()))

public fun Backend.count(session: SqlSession, filter: Filter): Outcome<Long, DbError> =
    when (val rows = session.query(raw("SELECT COUNT(*) FROM {p}punishments p" + filter.where, *filter.args().toTypedArray()))) {
        is Outcome.Err -> rows
        is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.long(0) ?: 0)
    }

/** The id just above the first one of the `skip + 1`th newest, to start a page jumped to without its previous page's last id. */
public fun Backend.boundary(session: SqlSession, filter: Filter, skip: Long): Outcome<Long, DbError> {
    if (skip <= 0) return Outcome.Ok(Long.MAX_VALUE)
    return when (val rows = session.query(raw("SELECT p.id FROM {p}punishments p" + filter.where + " ORDER BY p.id DESC LIMIT 1 OFFSET ${skip - 1}", *filter.args().toTypedArray()))) {
        is Outcome.Err -> rows
        is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.long(0) ?: Long.MIN_VALUE)
    }
}

