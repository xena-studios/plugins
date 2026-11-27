package co.xenastudios.xbans.store

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.Removal
import co.xenastudios.xbans.model.Entry
import kotlin.time.Instant

/** Reading and writing 1.x's rows: uuids as their 36 characters, times as epoch milliseconds, flags as 0 and 1. */
internal object Rows {
    fun code(type: PunishmentType): Int = when (type) {
        PunishmentType.BAN -> 1
        PunishmentType.MUTE -> 2
        PunishmentType.WARN -> 3
        PunishmentType.KICK -> 4
    }

    fun type(code: Int): PunishmentType = when (code) {
        1 -> PunishmentType.BAN
        2 -> PunishmentType.MUTE
        3 -> PunishmentType.WARN
        else -> PunishmentType.KICK
    }

    /** One row of [Tables.SELECT]. */
    fun read(row: Row): Entry {
        val removedAt = long(row, "r_removed")
        val removal = removedAt?.let {
            Removal(uuid(text(row, "r_staff_uuid")), text(row, "r_staff_name") ?: "?", Instant.fromEpochMilliseconds(it), text(row, "r_reason") ?: "")
        }
        val expires = long(row, "expires") ?: 0
        val punishment = Punishment(
            long(row, "id") ?: 0,
            type((long(row, "type") ?: 4).toInt()),
            uuid(text(row, "uuid")),
            text(row, "name") ?: "?",
            (long(row, "ip_based") ?: 0) != 0L,
            text(row, "reason") ?: "",
            uuid(text(row, "staff_uuid")),
            text(row, "staff_name") ?: "?",
            Instant.fromEpochMilliseconds(long(row, "created") ?: 0),
            if (expires <= 0) null else Instant.fromEpochMilliseconds(expires),
            text(row, "scope") ?: Punishment.GLOBAL,
            (long(row, "silent") ?: 0) != 0L,
            text(row, "template"),
            removal,
        )
        return Entry(punishment, text(row, "ip"), (long(row, "staff_weight") ?: 0).toInt())
    }

    /** [Tables.INSERT]'s values for [entry], made on the server called [origin]. */
    fun insert(entry: Entry, origin: String, imported: String?): List<SqlValue> {
        val p = entry.punishment
        return listOf(
            int(code(p.type)), uuidValue(p.target), SqlValue.Text(clip(p.targetName, 64)), nullable(entry.ip),
            int(if (p.ipBased) 1 else 0), SqlValue.Text(clip(p.reason, 1024)), uuidValue(p.staff), SqlValue.Text(clip(p.staffName, 64)),
            SqlValue.Integer(p.created.toEpochMilliseconds()), SqlValue.Integer(p.expires?.toEpochMilliseconds() ?: 0),
            SqlValue.Text(clip(p.scope, 64)), SqlValue.Text(clip(origin, 64)), int(if (p.silent) 1 else 0),
            nullable(p.template?.let { clip(it, 64) }), nullable(imported?.let { clip(it, 96) }), int(entry.staffWeight),
        )
    }

    fun int(value: Int): SqlValue = SqlValue.Integer(value.toLong())

    fun nullable(text: String?): SqlValue = if (text == null) SqlValue.Null else SqlValue.Text(text)

    fun uuidValue(player: PlayerRef?): SqlValue = if (player == null) SqlValue.Null else SqlValue.Text(player.toString())

    fun uuid(text: String?): PlayerRef? = if (text == null || text.length != 36) null else PlayerRef.parseOrNull(text)

    fun text(row: Row, name: String): String? = when (val value = row.value(name)) {
        is SqlValue.Text -> value.value
        is SqlValue.Integer -> value.value.toString()
        else -> null
    }

    fun long(row: Row, name: String): Long? = when (val value = row.value(name)) {
        is SqlValue.Integer -> value.value
        is SqlValue.Real -> value.value.toLong()
        is SqlValue.Text -> value.value.toLongOrNull()
        else -> null
    }

    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.substring(0, max)
}
