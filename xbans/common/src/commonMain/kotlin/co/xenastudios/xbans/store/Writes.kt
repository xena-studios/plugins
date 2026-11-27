package co.xenastudios.xbans.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.Removal
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.store.Backend.Companion.CONFLICT
import co.xenastudios.xbans.store.Backend.Companion.REPLACED
import co.xenastudios.xbans.store.Backend.Companion.TOPIC_PUNISH
import co.xenastudios.xbans.store.Backend.Companion.TOPIC_REVOKE
import co.xenastudios.xbans.store.Backend.Companion.first
import co.xenastudios.xbans.store.Backend.Companion.long
import co.xenastudios.xbans.store.Backend.Companion.text
import co.xenastudios.xbans.store.Backend.Stored

// [Backend]'s writes, apart from what runs as the server starts (what's in force), so their code
// loads with the first punishment or join rather than at startup.

/**
 * Stores a punishment made on the server called [origin], first revoking those it replaces,
 * and tells the network ([feedPayload] after the id). If one it replaces turns out to be
 * revoked already, nothing is stored and it fails with a [CONFLICT] error naming it: two
 * replacements at once can't leave two in force.
 */
public fun Backend.insert(draft: Entry, replaces: List<Entry>, replacedBy: Removal?, origin: String, feedPayload: String): Pending<Outcome<Stored, DbError>> =
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
        for (old in replaces) publish(session, TOPIC_REVOKE, "${old.id}$REPLACED").let { if (it is Outcome.Err) return@transaction it }
        publish(session, TOPIC_PUNISH, "$id$feedPayload").let { if (it is Outcome.Err) return@transaction it }
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
public fun Backend.revoke(id: Long, removal: Removal, feedPayload: String): Pending<Outcome<Boolean, DbError>> = database.transaction { session ->
    when (val removed = removal(session, id, removal)) {
        is Outcome.Err -> removed
        is Outcome.Ok -> if (!removed.value) Outcome.Ok(false) else when (val told = publish(session, TOPIC_REVOKE, "$id$feedPayload")) {
            is Outcome.Err -> told
            is Outcome.Ok -> Outcome.Ok(true)
        }
    }
}

// One statement, so two servers revoking the same punishment at once can't both get in: the
// second's row is ignored (the key is the punishment), and it's told so.
private fun Backend.removal(session: SqlSession, id: Long, removal: Removal): Outcome<Boolean, DbError> =
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
public fun Backend.insertImported(session: SqlSession, entry: Entry, imported: String): Outcome<Long, DbError> {
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
public fun Backend.importPlayer(session: SqlSession, id: PlayerRef, name: String, first: Long, last: Long): Outcome<Int, DbError> =
    session.execute(raw(
        Tables.insertIgnore(session.dialect) + " INTO {p}players (uuid, name, first_seen, last_seen, weight, exempt) VALUES (?, ?, ?, ?, 0, 0)",
        text(id.toString()), text(Rows.clip(name, 32)), long(first), long(last),
    ))

/** Records a join: the player's name, weight and exemption, and their IP key if there is one. */
public fun Backend.seen(id: PlayerRef, name: String, ip: String?, weight: Int, exempt: Boolean, now: Long): Pending<Outcome<Unit, DbError>> =
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
public fun Backend.touchIp(session: SqlSession, id: PlayerRef, ip: String, first: Long, last: Long): Outcome<Unit, DbError> {
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
