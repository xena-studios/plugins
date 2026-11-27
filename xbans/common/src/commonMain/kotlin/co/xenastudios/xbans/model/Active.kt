package co.xenastudios.xbans.model

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.xbans.api.PunishmentType

/**
 * Every ban and mute in force, in memory, so the join check and the chat check never ask the
 * database (decisions/2026-11-18-xbans.md). Loaded once when the database opens, then kept up to
 * date by every punishment made here or, in network mode, on another server or the proxy.
 *
 * Safe from any thread: each player's or IP's entries are an immutable list, replaced whole, so
 * reads take no lock. Changes (adding, removing, a reload finishing) take this object's lock:
 * they're rare, and each touches several maps that must agree. Expiry is checked when read, so
 * nothing runs on a timer; an expired entry is dropped the next time it's read.
 */
public class Active {
    private val bans = concurrentMap<PlayerRef, List<Entry>>()
    private val mutes = concurrentMap<PlayerRef, List<Entry>>()
    private val ipBans = concurrentMap<String, List<Entry>>()
    private val ipMutes = concurrentMap<String, List<Entry>>()
    private val byId = concurrentMap<Long, Entry>()

    // While a reload from the database runs: the ids added or removed meanwhile, which it leaves alone.
    private var syncing = 0
    private val touched = HashSet<Long>()

    /** Adds a ban or mute that's in force. Anything else is ignored. */
    public fun add(entry: Entry) {
        locked(this) {
            val type = entry.type
            if (type != PunishmentType.BAN && type != PunishmentType.MUTE || entry.punishment.removal != null) return
            if (byId[entry.id] != null) return
            byId[entry.id] = entry
            touch(entry.id)
            val ban = type == PunishmentType.BAN
            entry.target?.let { append(if (ban) bans else mutes, it, entry) }
            if (entry.punishment.ipBased && entry.ip != null) append(if (ban) ipBans else ipMutes, entry.ip, entry)
        }
    }

    /** Takes a punishment out, as when it's revoked. Returns what was there, or `null`. */
    public fun remove(id: Long): Entry? = locked(this) {
        touch(id)
        val entry = byId.remove(id) ?: return@locked null
        val ban = entry.type == PunishmentType.BAN
        entry.target?.let { drop(if (ban) bans else mutes, it, id) }
        entry.ip?.let { drop(if (ban) ipBans else ipMutes, it, id) }
        entry
    }

    /** The punishment with this id, if it's a ban or mute in memory. */
    public fun byId(id: Long): Entry? = byId[id]

    /**
     * The ban that keeps this player out of [server]: on their account first, then on their IP.
     * `null` if none. A `null` [server] is the proxy's question: only bans on every server count.
     */
    public fun ban(player: PlayerRef, ip: String?, server: String?, now: Long): Entry? =
        first(bans, player, server, now) ?: ip?.let { first(ipBans, it, server, now) }

    /** The mute that applies to this player on [server], by account or IP. `null` if none. */
    public fun mute(player: PlayerRef, ip: String?, server: String?, now: Long): Entry? =
        first(mutes, player, server, now) ?: ip?.let { first(ipMutes, it, server, now) }

    /**
     * The bans or mutes on exactly this scope that touch this account or this IP key, to tell
     * whether a new one replaces them or is covered by them; with a `null` scope, on any (for
     * `/checkban`). Two map lookups, however many are in force.
     */
    public fun onScope(type: PunishmentType, player: PlayerRef?, ip: String?, scope: String?, now: Long): List<Entry> {
        val found = LinkedHashSet<Entry>()
        val byPlayer = if (type == PunishmentType.BAN) bans else mutes
        val byIp = if (type == PunishmentType.BAN) ipBans else ipMutes
        if (player != null) {
            for (entry in byPlayer[player].orEmpty()) if (entry.active(now) && (scope == null || entry.punishment.scope.equals(scope, true))) found += entry
        }
        if (ip != null) {
            for (entry in byIp[ip].orEmpty()) if (entry.active(now) && (scope == null || entry.punishment.scope.equals(scope, true))) found += entry
        }
        return found.toList()
    }

    /** Every ban or mute that applies on [server] (`null`: on any), newest first, for `/banlist`. Ended ones are dropped on the way. */
    public fun all(type: PunishmentType, server: String?, now: Long): List<Entry> {
        val found = ArrayList<Entry>()
        for (entry in byId.values.toList()) {
            if (!entry.active(now)) remove(entry.id)
            else if (entry.type == type && (server == null || entry.appliesOn(server))) found += entry
        }
        found.sortByDescending { it.id }
        return found
    }

    /** Up to [limit] names of players with a ban or mute in force, for completion. */
    public fun names(type: PunishmentType, now: Long, limit: Int): List<String> {
        val names = LinkedHashSet<String>()
        for (list in (if (type == PunishmentType.BAN) bans else mutes).values.toList()) {
            for (entry in list) {
                if (!entry.active(now)) remove(entry.id) else if (names.size < limit) names += entry.punishment.targetName
            }
            if (names.size >= limit) break
        }
        return names.toList()
    }

    /** How many bans and mutes are in memory, expired ones not yet dropped included. */
    public val size: Int get() = byId.size

    // ---- reloading from the database ----

    /** A reload under way. Begun before the database is read, so changes made meanwhile are noticed. */
    public inner class Sync internal constructor() {
        private var done = false

        /**
         * Brings memory in line with what the database holds in force: adds what's missing, drops
         * what isn't there any more, and leaves alone anything added or removed since the reload
         * began (memory is newer for those). Returns the entries that were added.
         */
        public fun finish(inForce: Collection<Entry>): List<Entry> = locked(this@Active) {
            try {
                val ids = HashSet<Long>()
                val added = ArrayList<Entry>()
                for (entry in inForce) {
                    ids += entry.id
                    if (entry.id !in touched && byId[entry.id] == null) {
                        add(entry)
                        added += entry
                    }
                }
                for (id in byId.keys.toList()) if (id !in ids && id !in touched) remove(id)
                added
            } finally {
                end()
            }
        }

        /** Ends the reload without changing anything, as when the database couldn't be read. */
        public fun abandon() {
            locked(this@Active) { end() }
        }

        private fun end() {
            if (done) return
            done = true
            syncing--
            if (syncing == 0) touched.clear()
        }
    }

    /** Starts a reload; call [Sync.finish] or [Sync.abandon] once the database has been read. */
    public fun beginSync(): Sync = locked(this) {
        syncing++
        Sync()
    }

    private fun touch(id: Long) {
        if (syncing > 0) touched += id
    }

    private fun <K : Any> append(map: MutableMap<K, List<Entry>>, key: K, entry: Entry) {
        map[key] = map[key].orEmpty() + entry
    }

    private fun <K : Any> drop(map: MutableMap<K, List<Entry>>, key: K, id: Long) {
        val kept = map[key]?.filter { it.id != id } ?: return
        if (kept.isEmpty()) map.remove(key) else map[key] = kept
    }

    private fun <K : Any> first(map: Map<K, List<Entry>>, key: K, server: String?, now: Long): Entry? {
        val list = map[key] ?: return null
        var found: Entry? = null
        for (entry in list) {
            if (!entry.active(now)) {
                // Ended on its own: forget it, so it's never looked at again.
                remove(entry.id)
            } else if (found == null && (if (server == null) entry.punishment.isGlobal else entry.appliesOn(server))) {
                found = entry
            }
        }
        return found
    }
}
