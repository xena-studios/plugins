package co.xenastudios.xteleport.store

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.locked
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.api.Warp
import kotlin.concurrent.Volatile

/** A write the database refused, for API futures that complete exceptionally. */
public class NotSavedException(message: String) : RuntimeException(message)

/** One player's homes by lower-case name, sorted, and whether they turned TPA requests off. Never changed. */
public class Loaded(public val homes: Map<String, Home>, public val tpaOff: Boolean) {
    public fun home(name: String): Home? = homes[Store.key(name)]

    public val list: List<Home> get() = homes.values.toList()

    internal fun with(home: Home): Loaded = Loaded(sorted(homes + (Store.key(home.name) to home)), tpaOff)

    internal fun without(name: String): Loaded = Loaded(homes - Store.key(name), tpaOff)

    public companion object {
        internal fun of(data: PlayerData): Loaded = Loaded(sorted(data.homes.associateBy { Store.key(it.name) }), data.tpaOff)

        private fun sorted(homes: Map<String, Home>): Map<String, Home> {
            val keys = homes.keys.sorted()
            val out = LinkedHashMap<String, Home>(keys.size * 2)
            for (key in keys) out[key] = homes.getValue(key)
            return out
        }
    }
}

/**
 * Online players' homes and TPA setting, in memory: read from the database as they join, changed
 * here and written through, forgotten as they leave. Safe from any thread. Another player's homes
 * (staff with `/homes <player>`) are read from the database each time unless that player is
 * online.
 */
public class Homes(private val reach: Reach) {
    /** Where the database is, who's online and the time: the plugin's, for each store in memory. */
    public interface Reach {
        public fun store(): Store?
        public fun online(player: PlayerRef): Boolean
        public fun now(): Long
    }

    private fun store(): Store? = reach.store()

    private fun online(player: PlayerRef): Boolean = reach.online(player)

    private fun now(): Long = reach.now()

    private val loaded = concurrentMap<PlayerRef, Loaded>()
    private val loading = concurrentMap<PlayerRef, Pending<Loaded>>()

    /** An online player's homes, if they've been read. */
    public fun cached(player: PlayerRef): Loaded? = loaded[player]

    /** A player's homes: from memory when they're online and read, otherwise from the database (and kept, if they're online). */
    public fun of(player: PlayerRef): Pending<Loaded> {
        loaded[player]?.let { return pendingOf(it) }
        val store = store() ?: return co.xenastudios.core.api.pendingFailure(NotSavedException("the database isn't open"))
        val future = newPending<Loaded>()
        val already = locked(loading) { loading[player] ?: future.also { loading[player] = it } }
        if (already !== future) return already
        store.loadPlayer(player).onSettled { outcome, error ->
            locked(loading) { loading.remove(player) }
            if (outcome !is Outcome.Ok) {
                future.reject(NotSavedException("couldn't read the homes: ${(outcome as? Outcome.Err)?.error ?: error}"))
                return@onSettled
            }
            val read = Loaded.of(outcome.value)
            if (online(player)) {
                // A change made while it was being read is newer than what was read.
                val kept = locked(loaded) { loaded[player] ?: read.also { loaded[player] = it } }
                future.resolve(kept)
            } else {
                future.resolve(read)
            }
        }
        return future
    }

    public fun forget(player: PlayerRef) {
        loaded.remove(player)
    }

    /** Forgets every player, so the next ask reads the database again, as after an import. */
    public fun forgetAll() {
        loaded.clear()
    }

    /** Whether an online player turned TPA requests off. False until they're read. */
    public fun tpaOff(player: PlayerRef): Boolean = loaded[player]?.tpaOff == true

    /** Sets a home, replacing one of the same name, and saves it: in memory at once, taken back out if the save fails. */
    public fun set(home: Home): Pending<Boolean> {
        val name = Store.key(home.name)
        var before: Home? = null
        val store = store() ?: return notOpen()
        return change(home.owner, store.saveHome(home, now()), { homes -> before = homes.homes[name]; homes.with(home) }) { homes ->
            if (homes.homes[name] !== home) homes else before?.let { homes.with(it) } ?: homes.without(name)
        }
    }

    /** Deletes a home: gone from memory at once, put back if the delete fails. */
    public fun delete(owner: PlayerRef, name: String): Pending<Boolean> {
        val key = Store.key(name)
        var before: Home? = null
        val store = store() ?: return notOpen()
        return change(owner, store.deleteHome(owner, name), { homes -> before = homes.homes[key]; homes.without(name) }) { homes ->
            val old = before
            if (old == null || key in homes.homes) homes else homes.with(old)
        }
    }

    /** Turns TPA requests off or on, and saves it; undone if the save fails. */
    public fun tpaOff(player: PlayerRef, off: Boolean): Pending<Boolean> {
        var before = false
        val store = store() ?: return notOpen()
        return change(player, store.setTpaOff(player, off), { homes -> before = homes.tpaOff; Loaded(homes.homes, off) }) { homes ->
            if (homes.tpaOff == off) Loaded(homes.homes, before) else homes
        }
    }

    // A change in memory now, and again once its write is done (a read of the player already
    // running stored what it read, which is older); taken back if the write fails, unless
    // something changed it since. Settles true once saved, or fails with why not.
    private fun change(player: PlayerRef, write: Pending<Outcome<Unit, DbError>>, apply: (Loaded) -> Loaded, undo: (Loaded) -> Loaded): Pending<Boolean> {
        val applied = locked(loaded) { loaded[player]?.let { loaded[player] = apply(it); true } ?: false }
        val result = newPending<Boolean>()
        write.onSettled { outcome, error ->
            if (outcome is Outcome.Ok) {
                locked(loaded) { loaded[player]?.let { loaded[player] = apply(it) } }
                result.resolve(true)
            } else {
                if (applied) locked(loaded) { loaded[player]?.let { loaded[player] = undo(it) } }
                result.reject(NotSavedException("couldn't save: ${(outcome as? Outcome.Err)?.error ?: error}"))
            }
        }
        return result
    }

    private fun notOpen(): Pending<Boolean> = co.xenastudios.core.api.pendingFailure(NotSavedException("the database isn't open"))
}

/**
 * The warps and the spawn, which the whole server shares: read once when the database opens,
 * kept in memory, changed here and written through. Safe from any thread.
 */
public class Places(private val reach: Homes.Reach) {
    private fun store(): Store? = reach.store()

    private fun now(): Long = reach.now()

    // Replaced whole on each change.
    @Volatile
    private var warps: Map<String, Warp> = emptyMap()

    @Volatile
    public var setSpawn: Warp? = null
        private set

    @Volatile
    public var loaded: Boolean = false
        private set

    private val lock = Any()

    /** Takes what was read from the database. */
    public fun load(data: ServerData) {
        locked(lock) {
            warps = sorted(data.warps.associateBy { Store.key(it.name) })
            setSpawn = data.spawn
            loaded = true
        }
    }

    public fun warps(): List<Warp> = warps.values.toList()

    public fun warp(name: String): Warp? = warps[Store.key(name)]

    /** Sets a warp and saves it: in memory at once, taken back out if the save fails. */
    public fun setWarp(warp: Warp): Pending<Boolean> {
        val store = store() ?: return co.xenastudios.core.api.pendingFailure(NotSavedException("the database isn't open"))
        val key = Store.key(warp.name)
        val before = put(key, warp)
        return settle(store.saveWarp(warp, now())) { saved -> if (saved) put(key, warp) else replaceIf(key, warp, before) }
    }

    /** Deletes a warp; put back if the delete fails. */
    public fun deleteWarp(name: String): Pending<Boolean> {
        val store = store() ?: return co.xenastudios.core.api.pendingFailure(NotSavedException("the database isn't open"))
        val key = Store.key(name)
        val before = put(key, null)
        return settle(store.deleteWarp(name)) { saved -> if (saved) put(key, null) else replaceIf(key, null, before) }
    }

    /** Sets the spawn and saves it; the old one comes back if the save fails. */
    public fun setSpawn(at: Location): Pending<Boolean> {
        val store = store() ?: return co.xenastudios.core.api.pendingFailure(NotSavedException("the database isn't open"))
        val spawn = Warp(Tables.SPAWN, at)
        val before = locked(lock) { setSpawn.also { setSpawn = spawn } }
        return settle(store.saveSpawn(at, now())) { saved ->
            locked(lock) {
                if (saved) setSpawn = spawn else if (setSpawn === spawn) setSpawn = before
            }
        }
    }

    private fun settle(write: Pending<Outcome<Unit, DbError>>, then: (Boolean) -> Unit): Pending<Boolean> {
        val result = newPending<Boolean>()
        write.onSettled { outcome, error ->
            val saved = outcome is Outcome.Ok
            then(saved)
            if (saved) result.resolve(true) else result.reject(NotSavedException("couldn't save: ${(outcome as? Outcome.Err)?.error ?: error}"))
        }
        return result
    }

    // Sets or removes one warp, returning what it replaced.
    private fun put(key: String, warp: Warp?): Warp? = locked(lock) {
        val copy = LinkedHashMap(warps)
        val before = if (warp == null) copy.remove(key) else copy.put(key, warp)
        warps = sorted(copy)
        before
    }

    // Undoes a change that failed to save, unless something changed that warp since.
    private fun replaceIf(key: String, expected: Warp?, restore: Warp?) {
        locked(lock) {
            if (warps[key] === expected && (expected != null || restore != null)) put(key, restore)
        }
    }

    private fun sorted(map: Map<String, Warp>): Map<String, Warp> {
        val out = LinkedHashMap<String, Warp>(map.size * 2)
        for (key in map.keys.sorted()) out[key] = map.getValue(key)
        return out
    }
}
