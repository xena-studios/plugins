package co.xenastudios.zchat

import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.map
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.Values
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.storage.FileRecordStore
import co.xenastudios.core.common.storage.RecordType
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * The choices zChat remembers per player when `remember` is on: hidden chat (`/togglechat`) and
 * social spy. In `players.records` in zChat's folder (3.x kept them on the player, in Bukkit's
 * persistent data, which Spigot before 1.14 and Fabric don't have; the Bukkit adapter reads
 * those once). All in memory once read, so a join reads a map; changes are written off the
 * server's threads. `/zchat forget <player>` deletes a player's.
 */
public class PlayerSettings(private val platform: Platform) : PlayerData {
    /** What's kept for one player. */
    public class Kept(public val chatHidden: Boolean, public val spy: Boolean) {
        public val isEmpty: Boolean get() = !chatHidden && !spy
    }

    private object Players : Table("zchat_players") {
        val uuid = column("uuid", ColumnType.UUID)
        val chatHidden = column("chat_hidden", ColumnType.BOOLEAN)
        val spy = column("social_spy", ColumnType.BOOLEAN)

        init {
            primaryKey(uuid)
        }
    }

    private class Record(val uuid: Uuid, val kept: Kept)

    private object Records : RecordType<Uuid, Record>(Players, Players.uuid) {
        override fun keyOf(value: Record): Uuid = value.uuid
        override fun write(value: Record, values: Values) {
            values[Players.uuid] = value.uuid
            values[Players.chatHidden] = value.kept.chatHidden
            values[Players.spy] = value.kept.spy
        }
        override fun read(row: Row): Record = Record(row[Players.uuid], Kept(row[Players.chatHidden], row[Players.spy]))
    }

    private val kept = concurrentMap<PlayerRef, Kept>()

    @Volatile
    private var store: FileRecordStore<Uuid, Record>? = null

    /** Reads the file. Off the server's threads; once, at startup. Returns what went wrong, if anything. */
    public fun open(): String? {
        val opened = when (val result = FileRecordStore.open(platform.storage, platform.scheduler, FILE, Records, platform.log)) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return "Couldn't read $FILE (${result.error.detail}), so remembered /togglechat and /socialspy choices start empty."
        }
        opened.all().onAll { records -> for (record in records) kept[PlayerRef.of(record.uuid)] = record.kept }
        store = opened
        return null
    }

    /** What's kept for [player], if anything. */
    public operator fun get(player: PlayerRef): Kept? = kept[player]

    /** Keeps [player]'s choices; none left forgets them. */
    public fun set(player: PlayerRef, value: Kept) {
        if (value.isEmpty) {
            if (kept.remove(player) != null) store?.remove(player.uuid)
            return
        }
        kept[player] = value
        store?.put(Record(player.uuid, value))
    }

    override fun forget(player: PlayerRef): Pending<Outcome<Long, String>> {
        val had = kept.remove(player) != null
        val store = store ?: return pendingOf(Outcome.Ok(if (had) 1L else 0L))
        return store.remove(player.uuid).map { result ->
            when (result) {
                is Outcome.Ok -> Outcome.Ok(if (had) 1L else 0L)
                is Outcome.Err -> Outcome.Err(result.error.detail)
            }
        }
    }

    private fun <T> Pending<Outcome<List<T>, DbError>>.onAll(block: (List<T>) -> Unit) {
        onSettled { value, _ -> (value as? Outcome.Ok)?.let { block(it.value) } }
    }

    private companion object {
        val FILE: StoragePath = StoragePath.of("players.records")
    }
}
