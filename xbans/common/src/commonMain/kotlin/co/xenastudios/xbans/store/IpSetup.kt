package co.xenastudios.xbans.store

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.xbans.ip.IpKeys

/**
 * How IPs are keyed for a database, as 1.x decided it (decisions/2026-11-18-xbans.md, "IP keys").
 * Hashing, once on, stays on; a hashed database opens only with the salt it was hashed with
 * (`ip-salt.txt` in xBans' folder), since with another no IP ban would match anyone, unless the
 * owner says the salt is lost for good (`reset-ip-salt.txt`). Turning hashing on hashes what's
 * stored in one transaction, claimed first, so of several servers starting at once one hashes and
 * the others use its salt. On the database's writer, outside any transaction.
 */
internal class IpSetup(internal val storage: Storage, internal val log: Log, internal val sql: Sql) {

    /** The keys [session]'s database uses, or why it can't be opened (in English, for the console). */
    fun keys(session: SqlSession, hash: Boolean): Outcome<IpKeys, String> {
        val mode = when (val read = meta(session, MODE)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return fail(read.error)
        }
        // Hashing's code (IpHashing.kt) loads only for a database that hashes.
        if (mode == "hashed" || hash) return hashing(session, mode == "hashed", hash)
        if (mode == null) setMeta(session, MODE, "raw").let { if (it is Outcome.Err) return fail(it.error) }
        return Outcome.Ok(IpKeys.raw())
    }

    internal fun fail(error: DbError): Outcome<Nothing, String> = Outcome.Err("couldn't read how IPs are kept: ${error.detail}")

    // 1.x's rows in the core's meta table: `ip-mode` and `ip-salt-check`, beside `schema`.
    internal fun meta(session: SqlSession, name: String): Outcome<String?, DbError> =
        when (val rows = session.query(sql.raw("SELECT value FROM {p}meta WHERE name = ?", SqlValue.Text(name)))) {
            is Outcome.Err -> rows
            is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.let { (it.values.firstOrNull() as? SqlValue.Text)?.value })
        }

    internal fun setMeta(session: SqlSession, name: String, value: String): Outcome<Unit, DbError> = setMetaIn(session, name, value)

    internal fun setMetaIn(session: SqlSession, name: String, value: String): Outcome<Unit, DbError> {
        val updated = session.execute(sql.raw("UPDATE {p}meta SET value = ? WHERE name = ?", SqlValue.Text(value), SqlValue.Text(name)))
        when (updated) {
            is Outcome.Err -> return updated
            is Outcome.Ok -> if (updated.value > 0) return Outcome.Ok(Unit)
        }
        return when (val inserted = session.execute(sql.raw("INSERT INTO {p}meta (name, value) VALUES (?, ?)", SqlValue.Text(name), SqlValue.Text(value)))) {
            is Outcome.Err -> inserted
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }

    internal companion object {
        const val MODE = "ip-mode"
        const val CHECK = "ip-salt-check"
        const val CLAIMED = "xbans:salt-claimed"
    }
}
