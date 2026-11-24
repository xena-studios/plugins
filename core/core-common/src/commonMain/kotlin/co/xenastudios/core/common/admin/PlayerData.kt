package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Column
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.eq
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import kotlin.uuid.Uuid

/**
 * Something a plugin keeps about each player, which `/<plugin> forget <player>` deletes: a
 * table's rows, a file, a cache. A plugin registers each one with its [PlayerDataRegistry].
 */
public fun interface PlayerData {
    /**
     * Deletes everything kept here about [player]; gives how many records went, or why it
     * couldn't, in English. Called on an async task; it may wait there, or answer later.
     */
    public fun forget(player: PlayerRef): Pending<Outcome<Long, String>>
}

/** Finds a player by name, online or not: the family's player cache, or the server's. */
public fun interface PlayerLookup {
    /** The player last seen as [name] (any case), or `null` if no one by that name has played here. */
    public fun find(name: String): Pending<PlayerRef?>
}

/** What forgetting one player did: [records] deleted in all, and what [failed], by name, with why. */
public data class ForgetReport(public val records: Long, public val failed: Map<String, String>)

/**
 * Everything a plugin keeps per player, by name, for `/<plugin> forget <player>`: a request
 * to be forgotten (GDPR's erasure, or a player's wish) is answered by one command, and a plugin
 * that adds a table later adds it here, so nothing is left behind.
 */
public class PlayerDataRegistry(
    /** Finds the player `forget` is given by name; a UUID is taken as it is. */
    public val lookup: PlayerLookup,
) {
    private val lock = Any()
    private val kept = LinkedHashMap<String, PlayerData>()

    /** What's registered, by name, in order. */
    public val names: List<String> get() = locked(lock) { kept.keys.toList() }

    /** Registers [data] as [name], such as `homes`. */
    public fun register(name: String, data: PlayerData) {
        locked(lock) {
            require(name !in kept) { "player data $name is registered twice" }
            kept[name] = data
        }
    }

    /** Registers the rows of [column]'s table whose [column] is the player: `table("homes", { db }, Homes.owner)`. */
    public fun table(name: String, database: () -> Database, column: Column<Uuid>) {
        register(name) { player ->
            val db = database()
            db.transaction { it.execute(db.sql.delete(column.table, column eq player.uuid)) }.toReport()
        }
    }

    /**
     * The player [nameOrUuid] names: a UUID (with or without dashes) as it is, a name through
     * [lookup]. `null` if it's neither.
     */
    public fun resolve(nameOrUuid: String): Pending<PlayerRef?> {
        val uuid = parseUuid(nameOrUuid)
        return if (uuid != null) pendingOf(PlayerRef.of(uuid)) else lookup.find(nameOrUuid)
    }

    /** Deletes everything registered about [player], all at once; one that fails doesn't stop the rest. */
    public fun forget(player: PlayerRef): Pending<ForgetReport> {
        val all = locked(lock) { kept.toList() }
        if (all.isEmpty()) return pendingOf(ForgetReport(0, emptyMap()))
        val done = newPending<ForgetReport>()
        val reportLock = Any()
        var records = 0L
        val failed = LinkedHashMap<String, String>()
        var left = all.size
        for ((name, data) in all) {
            val forgetting = try {
                data.forget(player)
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                pendingOf(Outcome.Err("it threw ${e::class.simpleName}: ${e.message}"))
            }
            forgetting.onSettled { result, error ->
                val report = locked(reportLock) {
                    when {
                        error != null -> failed[name] = error.toString()
                        result is Outcome.Err -> failed[name] = result.error
                        result is Outcome.Ok -> records += result.value
                    }
                    left--
                    if (left == 0) ForgetReport(records, all.map { it.first }.filter { it in failed }.associateWith { failed.getValue(it) }) else null
                }
                if (report != null) done.resolve(report)
            }
        }
        return done
    }

    private fun Pending<Outcome<Int, co.xenastudios.core.common.db.DbError>>.toReport(): Pending<Outcome<Long, String>> {
        val done = newPending<Outcome<Long, String>>()
        onSettled { result, error ->
            done.resolve(
                when {
                    error != null -> Outcome.Err(error.toString())
                    result is Outcome.Err -> Outcome.Err(result.error.toString())
                    else -> Outcome.Ok((result as Outcome.Ok).value.toLong())
                },
            )
        }
        return done
    }

    private companion object {
        fun parseUuid(text: String): Uuid? = when (text.length) {
            36 -> runCatching { Uuid.parse(text) }.getOrNull()
            32 -> runCatching { Uuid.parseHex(text) }.getOrNull()
            else -> null
        }
    }
}
