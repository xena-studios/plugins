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
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.eq
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.db.queryOne
import co.xenastudios.core.common.db.values
import co.xenastudios.xvanish.config.XVanishConfig

/**
 * Network mode: one table of who's vanished, `{prefix}vanished`, in a MySQL or MariaDB database
 * every server shares, and the core's change feed (topic `vanish`, the player's uuid) so a change
 * on one server reaches the others within `network.check-every`. Every server keeps the whole
 * table in memory, so a join reads only memory, as on a single server. Off, and its classes never
 * loaded, unless `network.enabled` is on.
 */
public class Network private constructor(
    private val plugin: XVanishPlugin,
    /** The shared database. */
    public val database: Database,
    private val feed: ChangeFeed,
) {
    // Who the network says is vanished, by player: their name.
    private val vanished = concurrentMap<PlayerRef, String>()

    /** Whether the network says [player] is vanished. From memory. */
    public fun isVanished(player: PlayerRef): Boolean = player in vanished

    /** The names of the network's vanished players who aren't in [here]. */
    public fun elsewhere(here: Collection<PlayerRef>): List<String> = vanished.filterKeys { it !in here }.values.toList()

    /** Tells the network [player] vanished or showed: the table and the feed, in one write. */
    public fun publish(player: PlayerRef, name: String, vanished: Boolean) {
        if (vanished) this.vanished[player] = name else this.vanished.remove(player)
        val sql = database.sql
        val change = if (vanished) {
            sql.upsert(values(Vanished) {
                it[Vanished.uuid] = player.toString()
                it[Vanished.name] = name
                it[Vanished.since] = plugin.platform.clock.now().toEpochMilliseconds()
            })
        } else {
            listOf(sql.delete(Vanished, Vanished.uuid eq player.toString()))
        }
        database.write(change + feed.publishing(TOPIC, player.toString())).onSettled { result, error ->
            if (result !is Outcome.Ok) plugin.log.warn("Couldn't tell the network $name ${if (vanished) "vanished" else "showed again"}: ${(result as? Outcome.Err)?.error ?: error}")
        }
    }

    /** `/xvanish forget`: [player]'s row, and the others told. */
    public fun forget(player: PlayerRef): Pending<Outcome<Long, String>> {
        val had = vanished.remove(player) != null
        val done = newPending<Outcome<Long, String>>()
        database.write(listOf(database.sql.delete(Vanished, Vanished.uuid eq player.toString())) + feed.publishing(TOPIC, player.toString())).onSettled { result, error ->
            done.resolve(if (result is Outcome.Ok) Outcome.Ok(if (had) 1L else 0L) else Outcome.Err("${(result as? Outcome.Err)?.error ?: error}"))
        }
        return done
    }

    private fun heard(uuid: String) {
        val player = PlayerRef.parseOrNull(uuid) ?: return
        database.read { session -> session.queryOne(database.sql.select(Vanished, Vanished.uuid eq uuid)) { it[Vanished.name] } }.onSettled { result, _ ->
            if (result !is Outcome.Ok) return@onSettled
            val name = result.value
            if (name != null) vanished[player] = name else vanished.remove(player)
            plugin.heard(player, name != null)
        }
    }

    private fun readAll(): Pending<Boolean> {
        val done = newPending<Boolean>()
        database.read { session -> session.query(database.sql.select(Vanished)) }.onSettled { result, error ->
            if (result is Outcome.Ok) {
                val read = result.value.associate { PlayerRef.parseOrNull(it[Vanished.uuid]) to it[Vanished.name] }
                for (gone in vanished.keys.filter { it !in read }) {
                    vanished.remove(gone)
                    plugin.heard(gone, false)
                }
                for ((player, name) in read) {
                    if (player == null) continue
                    if (vanished.put(player, name) == null) plugin.heard(player, true)
                }
                done.resolve(true)
            } else {
                plugin.log.warn("Couldn't read the network's vanished players: ${(result as? Outcome.Err)?.error ?: error}")
                done.resolve(false)
            }
        }
        return done
    }

    /** Stops the feed and closes the database. */
    public fun close(): Pending<Unit> {
        feed.close()
        return database.close()
    }

    /** `{prefix}vanished`: who's vanished on the network. */
    public object Vanished : Table("vanished") {
        public val uuid: co.xenastudios.core.common.db.Column<String> = column("uuid", ColumnType.text(36))
        public val name: co.xenastudios.core.common.db.Column<String> = column("name", ColumnType.text(16))
        public val since: co.xenastudios.core.common.db.Column<Long> = column("since", ColumnType.LONG)

        init {
            primaryKey(uuid)
        }
    }

    internal companion object {
        const val TOPIC = "vanish"

        val MIGRATIONS = listOf(Migration(1, "who's vanished on the network") { sql -> sql.createTable(Vanished) })

        /** Makes the table, opens the feed, reads who's vanished, then hands the network to [opened] and starts the feed. */
        fun open(plugin: XVanishPlugin, database: Database, opened: (Network) -> Unit) {
            val p = plugin.platform
            database.migrate(MIGRATIONS).onSettled { migrated, error ->
                if (migrated !is Outcome.Ok) {
                    plugin.log.error("Couldn't make the network's table in ${database.description}: ${(migrated as? Outcome.Err)?.error ?: error}. Network mode is off.")
                    database.close()
                    return@onSettled
                }
                ChangeFeed.open(database, XVanishConfig.network.read(plugin.config), p.scheduler, p.clock, plugin.log).onSettled { result, problem ->
                    val feed = (result as? Outcome.Ok)?.value
                    if (feed == null) {
                        plugin.log.error("Couldn't start the network's change feed: ${(result as? Outcome.Err)?.error ?: problem}. Network mode is off.")
                        database.close()
                        return@onSettled
                    }
                    val network = Network(plugin, database, feed)
                    feed.subscribe(TOPIC) { network.heard(it.payload) }
                    feed.onResync { network.readAll() }
                    network.readAll().onSettled { _, _ ->
                        opened(network)
                        feed.start()
                        plugin.log.info("Network mode: ${network.vanished.size} vanished on the network, from ${database.description}.")
                    }
                }
            }
        }
    }
}
