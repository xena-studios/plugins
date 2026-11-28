package co.xenastudios.xstats.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.xstats.data.Event
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.Numbers
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.stat.Vanilla
import org.bukkit.entity.Player

/**
 * Reading a player for a save, a join or a quit, on their thread: Minecraft's own counters as
 * [BukkitStatsServer] prepared them, and placeholder stats through PlaceholderAPI. Made with the
 * first reading, not as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal class Reading(private val platform: BukkitPlatform) {
    @Volatile
    private var placeholderFailed = false

    /** Players left out of leaderboards have it; read at each reading, so it follows LuckPerms. */
    private val exclude = "xstats.leaderboard.exclude"

    fun read(player: PlayerRef, stats: Catalog.Snapshot, quit: Boolean, papi: ((Player, String) -> String)?): Event.Sample? {
        val online = platform.plugin.server.getPlayer(player.javaUuid) ?: return null
        val raw = LongArray(stats.width) { Event.Sample.NOT_READ }
        for (stat in stats.stats) {
            when (val source = stat.source) {
                is StatDef.Vanillas -> {
                    val prepared = source.prepared as? BukkitStatsServer.Prepared ?: continue
                    var sum = 0L
                    for (term in prepared.terms) sum += read(online, term)
                    raw[stat.index] = sum
                }
                is StatDef.Placeholder -> if (papi != null) placeholder(online, papi, source.placeholder, stat.decimals)?.let { raw[stat.index] = it }
                else -> {}
            }
        }
        val look = online.location
        return Event.Sample(player, online.name, System.currentTimeMillis(), raw, look.yaw, look.pitch, online.hasPermission(exclude), quit)
    }

    private fun read(player: Player, term: BukkitStatsServer.Term): Long {
        val statistic = term.statistic ?: return 0
        var sum = 0L
        // Each in its own try: a block, item or mob this server lists but keeps no stat for throws.
        when {
            term.term.category == Vanilla.Category.CUSTOM -> sum = try {
                player.getStatistic(statistic).toLong()
            } catch (e: IllegalArgumentException) {
                0
            }
            term.entities.isNotEmpty() -> for (entity in term.entities) {
                try {
                    sum += player.getStatistic(statistic, entity)
                } catch (e: IllegalArgumentException) {
                    // Not one of its mobs.
                }
            }
            else -> for (material in term.materials) {
                try {
                    sum += player.getStatistic(statistic, material)
                } catch (e: IllegalArgumentException) {
                    // Not one of its blocks or items.
                }
            }
        }
        return Vanilla.stored(term.term, sum)
    }

    private fun placeholder(player: Player, fill: (Player, String) -> String, placeholder: String, decimals: Int): Long? {
        if (placeholderFailed) return null
        return try {
            val filled = fill(player, placeholder)
            if (filled == placeholder) null else Numbers.parse(filled, decimals)
        } catch (e: RuntimeException) {
            placeholderFailed = true
            platform.log.warn("Couldn't fill in $placeholder for a stat: $e. Placeholder stats are skipped until the next reload.")
            null
        } catch (e: LinkageError) {
            placeholderFailed = true
            null
        }
    }

    fun reset() {
        placeholderFailed = false
    }
}
