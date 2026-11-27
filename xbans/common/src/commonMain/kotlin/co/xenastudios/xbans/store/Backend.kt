package co.xenastudios.xbans.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.Removal
import co.xenastudios.xbans.ip.IpKeys
import co.xenastudios.xbans.model.Active
import co.xenastudios.xbans.model.Entry

/**
 * What's open: the database, its change feed, what's in force, and how IPs are keyed; and every
 * query xBans makes, 1.x's statements on the core's database layer. Writes run on the database's
 * one writer, each in a transaction with the network's change, so the other servers never hear of
 * a change that rolled back; reads run beside it. Every [Pending] settles off the server's
 * threads, with an [Outcome], never a failure.
 */
public class Backend(
    public val database: Database,
    public val feed: ChangeFeed,
    public val active: Active,
    public val keys: IpKeys,
) {
    private val sql get() = database.sql

    private fun raw(text: String, vararg args: SqlValue): Statement = sql.raw(text, *args)

    /** A stored punishment, and (for a warning) how many the player now has in force, counted in the same transaction. */
    public class Stored(public val entry: Entry, public val warnings: Int)

    // ---- what's in force ----

    /** Every ban and mute in force at [now]. The index on (type, expires) keeps this to the rows wanted. */
    public fun inForce(session: SqlSession, now: Long): Outcome<List<Entry>, DbError> =
        rows(session, raw(Tables.SELECT + " WHERE p.type IN (1, 2) AND (p.expires = 0 OR p.expires > ?) AND r.punishment IS NULL", long(now)))

    /**
     * Reads every ban and mute in force again and brings memory in line with it: for when this
     * server may have missed changes. Anything that changed in memory while it was read is left as
     * it is. Settles with the bans and mutes that were missing from memory, to be enforced.
     */
    public fun reloadInForce(now: Long): Pending<Outcome<List<Entry>, DbError>> {
        val sync = active.beginSync()
        return database.read { session ->
            when (val found = inForce(session, now)) {
                is Outcome.Ok -> Outcome.Ok(sync.finish(found.value))
                is Outcome.Err -> found.also { sync.abandon() }
            }
        }
    }

    // ---- writes ----

    /**
     * Stores a punishment made on the server called [origin], first revoking those it replaces,
     * and tells the network ([feedPayload] after the id). If one it replaces turns out to be
     * revoked already, nothing is stored and it fails with a [CONFLICT] error naming it: two
     * replacements at once can't leave two in force.
     */
    public fun insert(draft: Entry, replaces: List<Entry>, replacedBy: Removal?, origin: String, feedPayload: String): Pending<Outcome<Stored, DbError>> =
        database.transaction { session ->
            for (old in replaces) {
                when (val removed = removal(session, old.id, replacedBy!!)) {
                    is Outcome.Err -> return@transaction removed
                    is Outcome.Ok -> if (!removed.value) return@transaction Outcome.Err(DbError(DbError.Kind.CONSTRAINT, CONFLICT + old.id))
                }
            }
            val id = when (val inserted = session.insertForId(raw(Tables.INSERT, *Rows.insert(draft, origin, null).toTypedArray()))) {
                is Outcome.Ok -> inserted.value
                is Outcome.Err -> return@transaction inserted
            }
            for (old in replaces) feed.publish(session, TOPIC_REVOKE, "${old.id}$REPLACED").let { if (it is Outcome.Err) return@transaction it }
            feed.publish(session, TOPIC_PUNISH, "$id$feedPayload").let { if (it is Outcome.Err) return@transaction it }
            val target = draft.target
            val warnings = if (draft.type == PunishmentType.WARN && target != null) {
                when (val found = activeWarnings(session, target, draft.punishment.created.toEpochMilliseconds())) {
                    is Outcome.Ok -> found.value.size
                    is Outcome.Err -> return@transaction found
                }
            } else {
                0
            }
            Outcome.Ok(Stored(draft.withId(id), warnings))
        }

    /** Records that punishment [id] was revoked, and tells the network. `false` if it had been revoked already (on another server, say). */
    public fun revoke(id: Long, removal: Removal, feedPayload: String): Pending<Outcome<Boolean, DbError>> = database.transaction { session ->
        when (val removed = removal(session, id, removal)) {
            is Outcome.Err -> removed
            is Outcome.Ok -> if (!removed.value) Outcome.Ok(false) else when (val told = feed.publish(session, TOPIC_REVOKE, "$id$feedPayload")) {
                is Outcome.Err -> told
                is Outcome.Ok -> Outcome.Ok(true)
            }
        }
    }

    // One statement, so two servers revoking the same punishment at once can't both get in: the
    // second's row is ignored (the key is the punishment), and it's told so.
    private fun removal(session: SqlSession, id: Long, removal: Removal): Outcome<Boolean, DbError> =
        when (val changed = session.execute(raw(
            Tables.insertIgnore(session.dialect) + " INTO {p}removals (punishment, staff_uuid, staff_name, removed, reason) VALUES (?, ?, ?, ?, ?)",
            long(id), Rows.uuidValue(removal.staff), text(Rows.clip(removal.staffName, 64)), long(removal.at.toEpochMilliseconds()),
            text(Rows.clip(removal.reason, 1024)),
        ))) {
            is Outcome.Ok -> Outcome.Ok(changed.value > 0)
            is Outcome.Err -> changed
        }

    /**
     * Stores an imported punishment, with its removal if it had one, unless a row from the same
     * place was imported before. Part of the caller's transaction. Its new id, or -1 if it was
     * already imported.
     */
    public fun insertImported(session: SqlSession, entry: Entry, imported: String): Outcome<Long, DbError> {
        when (val exists = session.query(raw("SELECT 1 FROM {p}punishments WHERE imported = ?", text(Rows.clip(imported, 96))))) {
            is Outcome.Err -> return exists
            is Outcome.Ok -> if (exists.value.isNotEmpty()) return Outcome.Ok(-1)
        }
        val id = when (val inserted = session.insertForId(raw(Tables.INSERT, *Rows.insert(entry, "import", imported).toTypedArray()))) {
            is Outcome.Ok -> inserted.value
            is Outcome.Err -> return inserted
        }
        entry.punishment.removal?.let { removal(session, id, it).let { done -> if (done is Outcome.Err) return done } }
        return Outcome.Ok(id)
    }

    /** Adds a player an import knows about, unless they're already known. Part of the caller's transaction. */
    public fun importPlayer(session: SqlSession, id: PlayerRef, name: String, first: Long, last: Long): Outcome<Int, DbError> =
        session.execute(raw(
            Tables.insertIgnore(session.dialect) + " INTO {p}players (uuid, name, first_seen, last_seen, weight, exempt) VALUES (?, ?, ?, ?, 0, 0)",
            text(id.toString()), text(Rows.clip(name, 32)), long(first), long(last),
        ))

    /** Records a join: the player's name, weight and exemption, and their IP key if there is one. */
    public fun seen(id: PlayerRef, name: String, ip: String?, weight: Int, exempt: Boolean, now: Long): Pending<Outcome<Unit, DbError>> =
        database.transaction { session ->
            val updated = session.execute(raw(
                "UPDATE {p}players SET name = ?, last_seen = ?, weight = ?, exempt = ? WHERE uuid = ?",
                text(Rows.clip(name, 32)), long(now), Rows.int(weight), Rows.int(if (exempt) 1 else 0), text(id.toString()),
            ))
            when (updated) {
                is Outcome.Err -> return@transaction updated
                is Outcome.Ok -> if (updated.value == 0) {
                    session.execute(raw(
                        "INSERT INTO {p}players (uuid, name, first_seen, last_seen, weight, exempt) VALUES (?, ?, ?, ?, ?, ?)",
                        text(id.toString()), text(Rows.clip(name, 32)), long(now), long(now), Rows.int(weight), Rows.int(if (exempt) 1 else 0),
                    )).let { if (it is Outcome.Err) return@transaction it }
                }
            }
            if (ip != null) touchIp(session, id, ip, now, now).let { if (it is Outcome.Err) return@transaction it }
            Outcome.Ok(Unit)
        }

    /** Adds or refreshes one account's use of an IP. Part of the caller's transaction. */
    public fun touchIp(session: SqlSession, id: PlayerRef, ip: String, first: Long, last: Long): Outcome<Unit, DbError> {
        when (val updated = session.execute(raw(
            "UPDATE {p}ips SET last_seen = ? WHERE uuid = ? AND ip = ? AND last_seen < ?", long(last), text(id.toString()), text(ip), long(last),
        ))) {
            is Outcome.Err -> return updated
            is Outcome.Ok -> if (updated.value > 0) return Outcome.Ok(Unit)
        }
        return when (val inserted = session.execute(raw(
            Tables.insertIgnore(session.dialect) + " INTO {p}ips (uuid, ip, first_seen, last_seen) VALUES (?, ?, ?, ?)",
            text(id.toString()), text(ip), long(first), long(last),
        ))) {
            is Outcome.Err -> inserted
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }

    /**
     * Deletes [target]'s punishments that are no longer in force at [now] (ended, revoked, kicks),
     * made at or after [since]: `/prunehistory`. With [dryRun], only counts them. How many.
     */
    public fun prune(target: PlayerRef, since: Long, now: Long, dryRun: Boolean): Pending<Outcome<Int, DbError>> {
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
    public fun forget(player: PlayerRef): Pending<Outcome<Int, DbError>> = database.transaction { session ->
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
    public fun find(id: Long): Pending<Outcome<Entry?, DbError>> = database.read { session -> find(session, id) }

    public fun find(session: SqlSession, id: Long): Outcome<Entry?, DbError> =
        when (val found = rows(session, raw(Tables.SELECT + " WHERE p.id = ?", long(id)))) {
            is Outcome.Ok -> Outcome.Ok(found.value.firstOrNull())
            is Outcome.Err -> found
        }

    /** [target]'s warnings in force at [now], newest first. */
    public fun activeWarnings(target: PlayerRef, now: Long): Pending<Outcome<List<Entry>, DbError>> = database.read { activeWarnings(it, target, now) }

    private fun activeWarnings(session: SqlSession, target: PlayerRef, now: Long): Outcome<List<Entry>, DbError> = rows(session, raw(
        Tables.SELECT + " WHERE p.uuid = ? AND p.type = 3 AND r.punishment IS NULL AND (p.expires = 0 OR p.expires > ?) ORDER BY p.id DESC",
        text(target.toString()), long(now),
    ))

    /**
     * A player's or an IP's bans or mutes in force, from the database rather than memory, so a
     * server that missed a change can still revoke what's there: by account, and IP punishments on
     * the IP key. Newest first.
     */
    public fun inForceOf(type: PunishmentType, target: PlayerRef?, ip: String?, now: Long): Pending<Outcome<List<Entry>, DbError>> =
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

    /** An account that has joined from an IP, with its weight and exemption when last seen. */
    public class Account(public val id: PlayerRef, public val name: String, public val weight: Int, public val exempt: Boolean)

    /** Every account seen on an IP key, for holding an IP punishment to their weights and exemptions. */
    public fun accountsOn(ip: String): Pending<Outcome<List<Account>, DbError>> = database.read { session ->
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
    public fun ladderStep(target: PlayerRef, template: String, since: Long): Pending<Outcome<Int, DbError>> = database.read { session ->
        when (val found = session.query(raw(
            "SELECT COUNT(*) FROM {p}punishments p LEFT JOIN {p}removals r ON r.punishment = p.id" +
                " WHERE p.uuid = ? AND p.template = ? AND p.created >= ? AND (r.punishment IS NULL OR r.reason = ?)",
            text(target.toString()), text(template), long(since), text(REPLACED_REASON),
        ))) {
            is Outcome.Err -> found
            is Outcome.Ok -> Outcome.Ok((found.value.firstOrNull()?.long(0) ?: 0).toInt())
        }
    }

    /** What's known of a player who has joined: their name, weight and exemption when last seen, and their newest IP key. */
    public class Known(public val name: String?, public val weight: Int, public val exempt: Boolean, public val lastIp: String?)

    public fun known(id: PlayerRef): Pending<Outcome<Known, DbError>> = database.read { session ->
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
    public fun idByName(name: String): Pending<Outcome<Pair<PlayerRef, String>?, DbError>> = database.read { session ->
        when (val rows = session.query(raw("SELECT uuid, name FROM {p}players WHERE name = ? ORDER BY last_seen DESC LIMIT 1", text(name)))) {
            is Outcome.Err -> rows
            is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.let { row -> Rows.uuid(Rows.text(row, "uuid"))?.let { it to (Rows.text(row, "name") ?: name) } })
        }
    }

    /** One IP an account used: its key and when it was first and last seen. */
    public class IpUse(public val id: PlayerRef?, public val name: String, public val ip: String, public val first: Long, public val last: Long)

    /** The IPs an account has used, newest first. */
    public fun ipHistory(id: PlayerRef): Pending<Outcome<List<IpUse>, DbError>> = database.read { session ->
        uses(session, raw(
            "SELECT i.uuid AS uuid, p.name AS name, i.ip AS ip, i.first_seen AS first_seen, i.last_seen AS last_seen FROM {p}ips i" +
                " LEFT JOIN {p}players p ON p.uuid = i.uuid WHERE i.uuid = ? ORDER BY i.last_seen DESC",
            text(id.toString()),
        ))
    }

    /** Other accounts that used any of [id]'s IPs (or, with no [id], this [ip] key), the most recent use of each first. */
    public fun alts(id: PlayerRef?, ip: String?): Pending<Outcome<List<IpUse>, DbError>> = database.read { session ->
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

    private fun uses(session: SqlSession, statement: Statement): Outcome<List<IpUse>, DbError> = when (val rows = session.query(statement)) {
        is Outcome.Err -> rows
        is Outcome.Ok -> Outcome.Ok(rows.value.map { row ->
            val id = Rows.uuid(Rows.text(row, "uuid"))
            IpUse(id, Rows.text(row, "name") ?: id.toString(), Rows.text(row, "ip") ?: "?", Rows.long(row, "first_seen") ?: 0, Rows.long(row, "last_seen") ?: 0)
        })
    }

    /** Which punishments a page lists: a player's (`uuid`), a staff member's (`staff_uuid`, `null` for the console); optionally one type. */
    public class Filter private constructor(private val column: String, private val value: String?, public val type: PunishmentType?) {
        internal val where: String get() = " WHERE p.$column" + (if (value == null) " IS NULL" else " = ?") + if (type == null) "" else " AND p.type = ?"

        internal fun args(): List<SqlValue> = buildList {
            if (value != null) add(SqlValue.Text(value))
            if (type != null) add(SqlValue.Integer(Rows.code(type).toLong()))
        }

        override fun toString(): String = "$column=$value/$type"

        public companion object {
            public fun target(id: PlayerRef, type: PunishmentType?): Filter = Filter("uuid", id.toString(), type)

            public fun staff(id: PlayerRef?, type: PunishmentType?): Filter = Filter("staff_uuid", id?.toString(), type)
        }
    }

    /** Up to [limit] punishments newer-first with ids below [before]: one page by key range, as fast deep in a long history as at its start. */
    public fun page(session: SqlSession, filter: Filter, before: Long, limit: Int): Outcome<List<Entry>, DbError> =
        rows(session, raw(Tables.SELECT + filter.where + " AND p.id < ? ORDER BY p.id DESC LIMIT $limit", *(filter.args() + long(before)).toTypedArray()))

    public fun count(session: SqlSession, filter: Filter): Outcome<Long, DbError> =
        when (val rows = session.query(raw("SELECT COUNT(*) FROM {p}punishments p" + filter.where, *filter.args().toTypedArray()))) {
            is Outcome.Err -> rows
            is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.long(0) ?: 0)
        }

    /** The id just above the first one of the `skip + 1`th newest, to start a page jumped to without its previous page's last id. */
    public fun boundary(session: SqlSession, filter: Filter, skip: Long): Outcome<Long, DbError> {
        if (skip <= 0) return Outcome.Ok(Long.MAX_VALUE)
        return when (val rows = session.query(raw("SELECT p.id FROM {p}punishments p" + filter.where + " ORDER BY p.id DESC LIMIT 1 OFFSET ${skip - 1}", *filter.args().toTypedArray()))) {
            is Outcome.Err -> rows
            is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.long(0) ?: Long.MIN_VALUE)
        }
    }

    private fun rows(session: SqlSession, statement: Statement): Outcome<List<Entry>, DbError> = when (val rows = session.query(statement)) {
        is Outcome.Err -> rows
        is Outcome.Ok -> Outcome.Ok(rows.value.map(Rows::read))
    }

    public companion object {
        public const val TOPIC_PUNISH: String = "punish"
        public const val TOPIC_REVOKE: String = "revoke"

        /** The reason on a ban or mute's removal when a longer one replaced it. It still counts toward its template's ladder. */
        public const val REPLACED_REASON: String = "Replaced by a newer one"

        /** After a revoked id in the feed: it was replaced by a new punishment, so it isn't announced. */
        public const val REPLACED: String = "|r"

        /** After a punished id in the feed: imported, so it's taken in without a broadcast. */
        public const val QUIET: String = "|q"

        /** After a revoked id in the feed: revoked silently. */
        public const val SILENT: String = "|s"

        /** After a punished or revoked id in the feed: extra silent (-S), so only the console is told. */
        public const val EXTRA_SILENT: String = "|S"

        /** A [DbError]'s detail when a punishment a new one replaces was revoked meanwhile, then its id. */
        public const val CONFLICT: String = "xbans:revoked-meanwhile:"

        /** Where an imported vanilla IP ban came from names its IP, keyed as the database keys it: `vanilla:ip:<key>:<created>`. */
        public const val VANILLA_IP: String = "vanilla:ip:"

        internal fun long(value: Long): SqlValue = SqlValue.Integer(value)

        internal fun text(value: String): SqlValue = SqlValue.Text(value)

        /** [row]'s first column as a number. */
        internal fun first(row: Row): Long? = row.long(0)
    }
}
