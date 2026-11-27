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
    /** Network mode's change feed, or `null` off a network: a single server never loads its classes. */
    public val feed: ChangeFeed?,
    public val active: Active,
    public val keys: IpKeys,
) {
    internal val sql get() = database.sql

    internal fun raw(text: String, vararg args: SqlValue): Statement = sql.raw(text, *args)

    /** Whether this database is shared with a network's other servers, which hear of every change. */
    public val shared: Boolean get() = feed != null

    /** Tells the network about a change, in [session]'s transaction; nothing off a network. */
    public fun publish(session: SqlSession, topic: String, payload: String): Outcome<Unit, DbError> =
        feed?.publish(session, topic, payload) ?: Outcome.Ok(Unit)

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

    /** An account that has joined from an IP, with its weight and exemption when last seen. */
    public class Account(public val id: PlayerRef, public val name: String, public val weight: Int, public val exempt: Boolean)

    /** What's known of a player who has joined: their name, weight and exemption when last seen, and their newest IP key. */
    public class Known(public val name: String?, public val weight: Int, public val exempt: Boolean, public val lastIp: String?)

    /** One IP an account used: its key and when it was first and last seen. */
    public class IpUse(public val id: PlayerRef?, public val name: String, public val ip: String, public val first: Long, public val last: Long)

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

    internal fun rows(session: SqlSession, statement: Statement): Outcome<List<Entry>, DbError> = when (val rows = session.query(statement)) {
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
