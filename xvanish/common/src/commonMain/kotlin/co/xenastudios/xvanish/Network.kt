package co.xenastudios.xvanish

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.eq
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xvanish.config.XVanishConfig
import kotlin.time.Duration.Companion.seconds

/**
 * Network mode: one table of who's vanished, `{prefix}vanished`, in a MySQL or MariaDB database
 * every server shares, and the core's change feed (topic `vanish`, the player's uuid) so a change
 * on one server reaches the others within `network.check-every`. Every server keeps the whole
 * table in memory, so a join reads only memory, as on a single server. Off, and its classes never
 * loaded, unless `network.enabled` is on.
 *
 * A row stays once a player has vanished, with `vanished` off once they show: so a server that
 * was down when they showed elsewhere knows its own `vanished.txt` is out of date as it starts,
 * and drops them from it, while one that lists a player the table has never heard of (network
 * mode just turned on, or an import) adds them. Each server's `vanished.txt` follows the table
 * as it hears of changes, so a server that can't reach the database keeps everyone vanished as
 * they were last known.
 */
public class Network private constructor(
    private val plugin: XVanishPlugin,
    /** The shared database. */
    public val database: Database,
    private val feed: ChangeFeed,
) {
    private class Known(val name: String, val level: Int, val vanished: Boolean)

    // The table, as last read or written here, by player.
    private val known = concurrentMap<PlayerRef, Known>()

    // Players with a write from this server not yet settled: what's read meanwhile is older than it.
    private val writing = HashMap<PlayerRef, Int>()
    private val lock = Any()

    // remember: false quits waiting out [GRACE] before they're shown on the network.
    private val leaving = concurrentMap<PlayerRef, Task>()

    /** Whether the network says [player] is vanished. From memory. */
    public fun isVanished(player: PlayerRef): Boolean = known[player]?.vanished == true

    /** The level [player] vanished at, as the network last said; 1 if it doesn't know. */
    public fun level(player: PlayerRef): Int = known[player]?.level ?: 1

    /** The names of the network's vanished players who aren't in [here]: on another server, or offline. */
    public fun elsewhere(here: Collection<PlayerRef>): List<String> =
        known.entries.filter { it.value.vanished && it.key !in here }.map { it.value.name }

    /** Tells the network [player] vanished or showed here: the table and the feed, in one write. */
    public fun publish(player: PlayerRef, name: String, level: Int, vanished: Boolean) {
        leaving.remove(player)?.cancel()
        known[player] = Known(name, level, vanished)
        write(player, name, if (vanished) "vanished" else "showed again", database.sql.upsert(values(Vanished) {
            it[Vanished.uuid] = player.toString()
            it[Vanished.name] = name
            it[Vanished.level] = level
            it[Vanished.vanished] = vanished
            it[Vanished.server] = if (vanished) feed.origin else ""
            it[Vanished.since] = plugin.platform.clock.now().toEpochMilliseconds()
        }))
    }

    /**
     * [player] left this server with `remember` off: shown on the network after [GRACE], unless
     * they reached another server first (which wrote itself into their row) or vanished here again.
     * A proxy connects a player to the next server before it lets go of the last, so a switch
     * never shows them. Here, at once: a relog shows them.
     */
    public fun left(player: PlayerRef, name: String) {
        val was = known[player] ?: return
        if (!was.vanished) return
        known[player] = Known(was.name, was.level, false)
        leaving.remove(player)?.cancel()
        leaving[player] = plugin.platform.scheduler.run(Where.Async, GRACE) { showLeft(player, name) }
    }

    private fun showLeft(player: PlayerRef, name: String) {
        if (leaving.remove(player) == null) return
        write(player, name, "left", database.sql.update(values(Vanished) {
            it[Vanished.vanished] = false
            it[Vanished.server] = ""
            it[Vanished.since] = plugin.platform.clock.now().toEpochMilliseconds()
        }, (Vanished.uuid eq player.toString()) and (Vanished.server eq feed.origin)))
    }

    /** `/xvanish forget`: [player] shown on the network, every server's `vanished.txt` too, and the others told. */
    public fun forget(player: PlayerRef): Pending<Outcome<Long, String>> {
        leaving.remove(player)?.cancel()
        val had = known[player]
        if (had != null) known[player] = Known(had.name, had.level, false)
        val done = newPending<Outcome<Long, String>>()
        val update = database.sql.update(values(Vanished) {
            it[Vanished.vanished] = false
            it[Vanished.server] = ""
            it[Vanished.since] = plugin.platform.clock.now().toEpochMilliseconds()
        }, Vanished.uuid eq player.toString())
        writing(player, +1)
        database.write(listOf(update) + feed.publishing(TOPIC, player.toString())).onSettled { result, error ->
            writing(player, -1)
            done.resolve(if (result is Outcome.Ok) Outcome.Ok(if (had?.vanished == true) 1L else 0L) else Outcome.Err("${(result as? Outcome.Err)?.error ?: error}"))
        }
        return done
    }

    private fun write(player: PlayerRef, name: String, what: String, change: List<Statement>) = write(player, name, what, *change.toTypedArray())

    private fun write(player: PlayerRef, name: String, what: String, vararg change: Statement) {
        writing(player, +1)
        // The database keeps a write through an outage and tries it again until it works.
        database.write(change.toList() + feed.publishing(TOPIC, player.toString())).onSettled { result, error ->
            writing(player, -1)
            if (result !is Outcome.Ok) plugin.log.warn("Couldn't tell the network $name $what: ${(result as? Outcome.Err)?.error ?: error}")
        }
    }

    private fun writing(player: PlayerRef, by: Int) = locked(lock) {
        val now = (writing[player] ?: 0) + by
        if (now <= 0) writing.remove(player) else writing[player] = now
    }

    private fun busy(player: PlayerRef): Boolean = locked(lock) { player in writing }

    private fun heard(uuid: String) {
        val player = PlayerRef.parseOrNull(uuid) ?: return
        if (busy(player)) return
        database.read { session -> session.query(database.sql.select(Vanished, Vanished.uuid eq uuid)) }.onSettled { result, _ ->
            // Unreadable: what's known stays, so whoever was vanished stays vanished.
            if (result !is Outcome.Ok) return@onSettled
            val row = result.value.firstOrNull()
            if (row == null) forgotten(player) else learn(player, read(row))
        }
    }

    // The table says [now] of [player]: memory, then the plugin, if it changed.
    private fun learn(player: PlayerRef, now: Known) {
        if (busy(player)) return
        val before = known.put(player, now)
        if (before == null || before.vanished != now.vanished || before.name != now.name) plugin.heard(player, now.name, now.vanished)
    }

    // The row is gone (deleted by hand): the network has no say on them now.
    private fun forgotten(player: PlayerRef) {
        if (busy(player)) return
        val before = known.remove(player) ?: return
        if (before.vanished) plugin.heard(player, before.name, false)
    }

    private fun read(row: Row) = Known(row[Vanished.name], row[Vanished.level], row[Vanished.vanished])

    // Everything again: as the network opens, and when the feed may have missed changes.
    private fun readAll(first: Boolean): Pending<Boolean> {
        val done = newPending<Boolean>()
        database.read { session -> session.query(database.sql.select(Vanished)) }.onSettled { result, error ->
            if (result !is Outcome.Ok) {
                plugin.log.warn("Couldn't read the network's vanished players: ${(result as? Outcome.Err)?.error ?: error}. Who was vanished stays vanished.")
                done.resolve(false)
                return@onSettled
            }
            val read = HashMap<PlayerRef, Known>()
            for (row in result.value) read[PlayerRef.parseOrNull(row[Vanished.uuid]) ?: continue] = read(row)
            for (gone in known.keys.filter { it !in read }) forgotten(gone)
            for ((player, now) in read) learn(player, now)
            if (first) {
                // What this server knows that the table has never heard of: network mode just
                // turned on, or vanished.txt filled by an import or while the database was away.
                val mine = plugin.remembered.all() + plugin.vanished().associateWith { plugin.server.name(it) ?: "" }
                for ((player, name) in mine) if (player !in read) publish(player, name, plugin.level(player), true)
            }
            done.resolve(true)
        }
        return done
    }

    /** Stops the feed, shows on the network whoever left with `remember` off, and closes the database. */
    public fun close(): Pending<Unit> {
        feed.close()
        for (player in leaving.keys.toList()) {
            leaving[player]?.cancel()
            showLeft(player, known[player]?.name ?: "")
        }
        return database.close()
    }

    /** `{prefix}vanished`: who's vanished on the network, and who was. */
    public object Vanished : Table("vanished") {
        public val uuid: co.xenastudios.core.common.db.Column<String> = column("uuid", ColumnType.text(36))
        public val name: co.xenastudios.core.common.db.Column<String> = column("name", ColumnType.text(16))

        /** The level they vanished at. */
        public val level: co.xenastudios.core.common.db.Column<Int> = column("level", ColumnType.INT)

        /** Whether they're vanished now; off once they show, so the row says they did. */
        public val vanished: co.xenastudios.core.common.db.Column<Boolean> = column("vanished", ColumnType.BOOLEAN)

        /** The server they last vanished or joined on (its change feed's id), so a remember: false quit there can't show them on the next one. */
        public val server: co.xenastudios.core.common.db.Column<String> = column("server", ColumnType.text(16))

        /** When the row last changed, by that server's clock, in milliseconds since 1970. */
        public val since: co.xenastudios.core.common.db.Column<Long> = column("since", ColumnType.LONG)

        init {
            primaryKey(uuid)
        }
    }

    internal companion object {
        const val TOPIC = "vanish"

        /** How long a remember: false quit waits before showing the player on the network: longer than a server switch takes. */
        val GRACE = 10.seconds

        val MIGRATIONS = listOf(Migration(1, "who's vanished on the network") { sql -> sql.createTable(Vanished) })

        /**
         * Makes the table, opens the feed, reads who's vanished, then hands the network to [opened]
         * and starts the feed. [anyDatabase] opens the feed on SQLite too, for tests.
         */
        fun open(plugin: XVanishPlugin, database: Database, anyDatabase: Boolean = false, opened: (Network) -> Unit) {
            val p = plugin.platform
            database.migrate(MIGRATIONS).onSettled { migrated, error ->
                if (migrated !is Outcome.Ok) {
                    plugin.log.error("Couldn't make the network's table in ${database.description}: ${(migrated as? Outcome.Err)?.error ?: error}. Network mode is off.")
                    database.close()
                    return@onSettled
                }
                val settings = XVanishConfig.network.read(plugin.config)
                val feeding: Pending<Outcome<ChangeFeed, DbError>> = if (anyDatabase) {
                    ChangeFeed.openOnAny(database, settings, p.scheduler, p.clock, plugin.log, kotlin.uuid.Uuid.random().toHexString().substring(0, 16))
                } else {
                    ChangeFeed.open(database, settings, p.scheduler, p.clock, plugin.log)
                }
                feeding.onSettled { result, problem ->
                    val feed = (result as? Outcome.Ok)?.value
                    if (feed == null || !feed.isOn) {
                        if (feed == null) plugin.log.error("Couldn't start the network's change feed: ${(result as? Outcome.Err)?.error ?: problem}. Network mode is off.")
                        database.close()
                        return@onSettled
                    }
                    val network = Network(plugin, database, feed)
                    feed.subscribe(TOPIC) { network.heard(it.payload) }
                    feed.onResync { network.readAll(first = false) }
                    network.readAll(first = true).onSettled { _, _ ->
                        opened(network)
                        feed.start()
                        plugin.log.info("Network mode: ${network.known.values.count { it.vanished }} vanished on the network, from ${database.description}.")
                    }
                }
            }
        }
    }
}
