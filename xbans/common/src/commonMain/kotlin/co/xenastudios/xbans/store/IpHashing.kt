package co.xenastudios.xbans.store

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.xbans.ip.IpKeys
import co.xenastudios.xbans.store.IpSetup.Companion.CHECK
import co.xenastudios.xbans.store.IpSetup.Companion.CLAIMED
import co.xenastudios.xbans.store.IpSetup.Companion.MODE
import kotlin.uuid.Uuid

// Apart from IpSetup, which runs at every start: what only a database whose IPs are hashed needs.

private val SALT_PATH = StoragePath.of(IpKeys.SALT_FILE)
private val RESET_PATH = StoragePath.of(IpKeys.RESET_FILE)

/** [IpSetup.keys] for a database that's [hashedAlready], or is to be because of [hash]. */
internal fun IpSetup.hashing(session: SqlSession, hashedAlready: Boolean, hash: Boolean): Outcome<IpKeys, String> {
    if (hashedAlready) return hashedKeys(session, hash)
    val salt = salt(create = true).let { (it as? Outcome.Ok)?.value ?: return Outcome.Err((it as Outcome.Err).error) }
    val keys = IpKeys.hashed(salt)
    val hashed = when (val read = hashAll(session, keys)) {
        is Outcome.Ok -> read.value
        is Outcome.Err -> return fail(read.error)
    }
    // Another server of the network turned hashing on first: its salt is the one.
    if (hashed < 0) return hashedKeys(session, hash)
    log.info("IPs are hashed from now on" + if (hashed > 0) "; hashed the $hashed already stored." else ".")
    return Outcome.Ok(keys)
}

private fun IpSetup.hashedKeys(session: SqlSession, hash: Boolean): Outcome<IpKeys, String> {
    val check = when (val read = meta(session, CHECK)) {
        is Outcome.Ok -> read.value
        is Outcome.Err -> return fail(read.error)
    }
    if (storage.read(RESET_PATH).getOrNull() != null) {
        // The owner says the salt is lost for good: start over with the one here (or a new one).
        val salt = when (val read = salt(create = true)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> {
                // Not a salt at all: kept aside, and a new one made.
                storage.read(SALT_PATH).getOrNull()?.let { storage.write(StoragePath.of(IpKeys.SALT_FILE + ".old"), it) }
                storage.delete(SALT_PATH)
                salt(create = true).let { (it as? Outcome.Ok)?.value ?: return Outcome.Err((it as Outcome.Err).error) }
            }
        }
        val keys = IpKeys.hashed(salt)
        setMeta(session, CHECK, keys.fingerprint()).let { if (it is Outcome.Err) return fail(it.error) }
        storage.delete(RESET_PATH)
        if (keys.fingerprint() != check) {
            log.warn("Started over with the salt in ${IpKeys.SALT_FILE}, as ${IpKeys.RESET_FILE} asked: IP bans and alts recorded before " +
                "no longer match anyone. Copy ${IpKeys.SALT_FILE} to every other server sharing this database.")
        }
        return Outcome.Ok(keys)
    }
    val salt = salt(create = false).let { (it as? Outcome.Ok)?.value ?: return Outcome.Err((it as Outcome.Err).error) }
    val keys = IpKeys.hashed(salt)
    if (check != null && check != keys.fingerprint()) {
        return Outcome.Err(
            "${IpKeys.SALT_FILE} isn't the one this database's IPs were hashed with, so no IP ban or alt would match. Copy the right one " +
                "into xBans' folder from the server that hashed them (or from a backup). ${IpKeys.LOST}",
        )
    }
    if (check == null) setMeta(session, CHECK, keys.fingerprint()).let { if (it is Outcome.Err) return fail(it.error) }
    if (!hash) log.warn("ips.hash is off, but this database's IPs are already hashed, so they stay hashed.")
    return Outcome.Ok(keys)
}

/**
 * The salt in xBans' folder. [create] makes a random one if there's none: only for a database
 * whose IPs aren't hashed yet. One already hashed needs the salt it was hashed with, and a new
 * one would quietly stop every IP ban and alt from matching.
 */
internal fun IpSetup.salt(create: Boolean): Outcome<ByteArray, String> {
    val text = when (val read = storage.readText(SALT_PATH)) {
        is Outcome.Ok -> read.value
        is Outcome.Err -> return Outcome.Err("couldn't read ${IpKeys.SALT_FILE}: ${read.error.detail}")
    }
    if (text != null) {
        return IpKeys.parseSalt(text)?.let { Outcome.Ok(it) } ?: Outcome.Err(
            "${IpKeys.SALT_FILE} should hold 64 hex digits. Put back the one this database's IPs were hashed with (from another server " +
                "of the network, or a backup). ${IpKeys.LOST}",
        )
    }
    if (!create) {
        return Outcome.Err(
            "${IpKeys.SALT_FILE} is missing, but this database's IPs are hashed with it. Copy it into xBans' folder from the server that " +
                "hashed them (or from a backup); without it no IP ban or alt matches. ${IpKeys.LOST}",
        )
    }
    // Two random UUIDs: the platform's secure random, 244 random bits.
    val salt = Uuid.random().toByteArray() + Uuid.random().toByteArray()
    return when (val written = storage.writeText(SALT_PATH, IpKeys.saltText(salt))) {
        is Outcome.Ok -> Outcome.Ok(salt)
        is Outcome.Err -> Outcome.Err("couldn't write ${IpKeys.SALT_FILE}: ${written.error.detail}")
    }
}

/**
 * Hashes every IP stored, in one transaction with the switch itself, so a crash leaves the
 * database as it was rather than half hashed (and hashed twice at the next start). The switch
 * is claimed first. How many addresses were hashed, or -1 if another server had claimed it.
 */
internal fun IpSetup.hashAll(session: SqlSession, keys: IpKeys): Outcome<Int, DbError> = session.transaction { tx ->
    val claimed = tx.execute(sql.raw("${Tables.insertIgnore(tx.dialect)} INTO {p}meta (name, value) VALUES ('$CHECK', ?)", SqlValue.Text(keys.fingerprint())))
    when (claimed) {
        is Outcome.Err -> return@transaction claimed
        is Outcome.Ok -> if (claimed.value == 0) return@transaction Outcome.Err(DbError(DbError.Kind.CONSTRAINT, CLAIMED))
    }
    val raw = when (val rows = tx.query(sql.raw("SELECT DISTINCT ip FROM {p}ips UNION SELECT DISTINCT ip FROM {p}punishments WHERE ip IS NOT NULL"))) {
        is Outcome.Err -> return@transaction rows
        is Outcome.Ok -> rows.value.mapNotNull { (it.values.firstOrNull() as? SqlValue.Text)?.value }
    }
    // An imported vanilla IP ban remembers where it came from by its IP, so the address is
    // swapped for its hash there too, before the IPs themselves are: it's matched by the row's ip.
    val imports = when (val rows = tx.query(sql.raw("SELECT id, ip, imported FROM {p}punishments WHERE imported LIKE ?", SqlValue.Text(Backend.VANILLA_IP + "%")))) {
        is Outcome.Err -> return@transaction rows
        is Outcome.Ok -> rows.value
    }
    for (row in imports) {
        val id = Rows.long(row, "id") ?: continue
        val ip = Rows.text(row, "ip")
        val imported = Rows.text(row, "imported") ?: continue
        val prefix = Backend.VANILLA_IP + ip
        val key = if (ip == null) "?" else keys.key(ip)
        // Its date after the address; one that doesn't start so (written by hand) keeps no part of it.
        val rest = if (ip != null && imported.startsWith(prefix)) imported.substring(prefix.length) else ":#$id"
        tx.execute(sql.raw("UPDATE {p}punishments SET imported = ? WHERE id = ?", SqlValue.Text(Backend.VANILLA_IP + key + rest), SqlValue.Integer(id)))
            .let { if (it is Outcome.Err) return@transaction it }
    }
    for (address in raw) {
        val key = keys.key(address)
        for (table in listOf("{p}ips", "{p}punishments")) {
            tx.execute(sql.raw("UPDATE $table SET ip = ? WHERE ip = ?", SqlValue.Text(key), SqlValue.Text(address))).let { if (it is Outcome.Err) return@transaction it }
        }
    }
    // An IP punishment with no account is named after its IP (masked, or the start of its hash).
    // Once the IPs are hashed, those names are too, so no part of an address is left.
    val unnamed = when (val rows = tx.query(sql.raw("SELECT id, ip FROM {p}punishments WHERE uuid IS NULL"))) {
        is Outcome.Err -> return@transaction rows
        is Outcome.Ok -> rows.value
    }
    for (row in unnamed) {
        val id = Rows.long(row, "id") ?: continue
        tx.execute(sql.raw("UPDATE {p}punishments SET name = ? WHERE id = ?", SqlValue.Text(IpKeys.label(Rows.text(row, "ip"))), SqlValue.Integer(id)))
            .let { if (it is Outcome.Err) return@transaction it }
    }
    when (val set = setMetaIn(tx, MODE, "hashed")) {
        is Outcome.Err -> set
        is Outcome.Ok -> Outcome.Ok(raw.size)
    }
}.let { if (it is Outcome.Err && it.error.detail == CLAIMED) Outcome.Ok(-1) else it }
