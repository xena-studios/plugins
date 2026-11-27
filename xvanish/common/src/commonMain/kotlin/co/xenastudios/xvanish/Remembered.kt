package co.xenastudios.xvanish

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Where

/**
 * Who stays vanished through a relog and a restart: `vanished.txt` in the plugin's folder, a
 * player a line (`<uuid> <name>`). Read once as the plugin starts, before anyone joins; written
 * whole on the next async task after each change (a temporary file renamed over it), so a crash
 * loses nothing and a join reads only memory.
 *
 * A file rather than each player's own data (persistent data on Paper): Spigot 1.8.8 and Fabric
 * have none, and a file lists who's vanished while they're offline too (decisions/2026-11-27-xvanish-1.md).
 */
public class Remembered(private val storage: Storage, private val scheduler: Scheduler, private val log: Log) {
    private val players = concurrentMap<PlayerRef, String>()
    private val lock = Any()
    private var writing = false
    private var again = false
    private var waiting = ArrayList<Pending<Unit>>()

    /** Reads the file. On the thread that starts the plugin: it's a few lines. */
    public fun load() {
        val text = when (val read = storage.readText(FILE)) {
            is Outcome.Ok -> read.value ?: return
            is Outcome.Err -> return log.warn("Couldn't read $FILE (${read.error.detail}): nobody stays vanished from before.")
        }
        players.clear()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val player = PlayerRef.parseOrNull(trimmed.substringBefore(' ')) ?: continue
            players[player] = trimmed.substringAfter(' ', "").trim()
        }
    }

    public operator fun contains(player: PlayerRef): Boolean = player in players

    /** Who's in the file, by player, with their name as last seen. */
    public fun all(): Map<PlayerRef, String> = HashMap(players)

    public fun add(player: PlayerRef, name: String) {
        if (players.put(player, name) != name) save()
    }

    public fun remove(player: PlayerRef): Boolean {
        val was = players.remove(player) != null
        if (was) save()
        return was
    }

    /** Settles once every change so far is written. */
    public fun flush(): Pending<Unit> = locked(lock) {
        if (!writing) pendingOf(Unit) else newPending<Unit>().also { waiting += it }
    }

    private fun save() {
        val start = locked(lock) {
            if (writing) {
                again = true
                false
            } else {
                writing = true
                true
            }
        }
        if (start) scheduler.run(Where.Async) { write() }
    }

    private fun write() {
        while (true) {
            val text = buildString {
                append("# Who stays vanished through a relog or restart: xVanish writes this as it changes.\n")
                for ((player, name) in players.entries.sortedBy { it.value.lowercase() }) append(player).append(' ').append(name).append('\n')
            }
            val written = storage.writeText(FILE, text)
            if (written is Outcome.Err) log.warn("Couldn't write $FILE (${written.error.detail}); tried again with the next change.")
            val done = locked(lock) {
                if (again) {
                    again = false
                    null
                } else {
                    writing = false
                    waiting.also { waiting = ArrayList() }
                }
            } ?: continue
            done.forEach { it.resolve(Unit) }
            return
        }
    }

    public companion object {
        public val FILE: StoragePath = StoragePath.of("vanished.txt")
    }
}
