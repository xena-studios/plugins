package co.xenastudios.xstats.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.text.Component
import co.xenastudios.xstats.BoardHost
import co.xenastudios.xstats.StatsServer
import co.xenastudios.xstats.data.Event
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.Numbers
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.stat.Vanilla
import co.xenastudios.xstats.stat.VanillaReader
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/**
 * xStats' [StatsServer] on Bukkit, 1.13 to the newest: players, their permissions, and reading
 * Minecraft's own counters, a map lookup each (`Player#getStatistic`), on each player's own thread.
 * Placeholder stats go through PlaceholderAPI while it's on ([placeholders]); boards are text
 * displays where the server has them (1.19.4 on), made by a class only those servers load.
 */
internal class BukkitStatsServer(private val platform: BukkitPlatform) : StatsServer, VanillaReader {
    private val bukkit = platform.plugin.server

    /** Fills in a placeholder for a player, while PlaceholderAPI is on; `null` otherwise. */
    @Volatile
    var placeholders: ((Player, String) -> String)? = null

    @Volatile
    private var placeholderFailed = false

    /** Players left out of leaderboards have it; read at each reading, so it follows LuckPerms. */
    private val exclude = "xstats.leaderboard.exclude"

    override val boards: BoardHost? = if (textDisplays()) co.xenastudios.xstats.bukkit.modern.TextBoards(platform) else null

    override val vanilla: VanillaReader get() = this

    override fun online(): List<PlayerRef> = bukkit.onlinePlayers.map { it.ref }

    override fun isOnline(player: PlayerRef): Boolean = bukkit.getPlayer(player.javaUuid) != null

    override fun send(player: PlayerRef, message: Component) {
        bukkit.getPlayer(player.javaUuid)?.let { platform.text.send(it, message) }
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = bukkit.getPlayer(player.javaUuid)?.hasPermission(node) ?: false

    override fun banned(): Set<PlayerRef> = bukkit.bannedPlayers.mapTo(HashSet()) { it.ref }

    override fun worlds(): List<String> = bukkit.worlds.map { it.name }

    override fun customStats(): Int = Statistic.values().count { it.type == Statistic.Type.UNTYPED }

    /**
     * Checks the table against the server's own: Paper's scoreboard criteria name each Bukkit stat by
     * its vanilla key, through CraftBukkit's mapping. `null` on a server without them (Spigot, old Paper).
     */
    override fun verifyTable(): List<String>? = if (criteria()) co.xenastudios.xstats.bukkit.modern.Criteria.verify() else null

    // ---- reading ----

    /** A term ready to read: its constant and, for a typed stat, the blocks, items or mobs to add up. */
    private class Term(val term: Vanilla.Term, val statistic: Statistic?, val materials: Array<Material>, val entities: Array<EntityType>)

    private class Prepared(val terms: List<Term>, override val missing: List<Vanilla.Term>) : VanillaReader.Prepared

    override fun prepare(terms: List<Vanilla.Term>): VanillaReader.Prepared {
        val ready = ArrayList<Term>()
        val missing = ArrayList<Vanilla.Term>()
        for (term in terms) {
            val made = term(term)
            if (made.statistic == null) missing += term
            ready += made
        }
        return Prepared(ready, missing)
    }

    @Suppress("DEPRECATION")
    private fun term(term: Vanilla.Term): Term {
        val category = term.category
        if (category == Vanilla.Category.CUSTOM) {
            val statistic = custom(term.name) ?: Vanilla.olderKeys(term.name).firstNotNullOfOrNull { custom(it) }
            return Term(term, statistic, NO_MATERIALS, NO_ENTITIES)
        }
        val statistic = when (category) {
            Vanilla.Category.MINED -> Statistic.MINE_BLOCK
            Vanilla.Category.CRAFTED -> Statistic.CRAFT_ITEM
            Vanilla.Category.USED -> Statistic.USE_ITEM
            Vanilla.Category.BROKEN -> Statistic.BREAK_ITEM
            Vanilla.Category.PICKED_UP -> Statistic.PICKUP
            Vanilla.Category.DROPPED -> Statistic.DROP
            Vanilla.Category.KILLED -> Statistic.KILL_ENTITY
            else -> Statistic.ENTITY_KILLED_BY
        }
        return when (category.of) {
            Vanilla.Category.Of.ENTITY -> {
                val entities = EntityType.values().filter { it != EntityType.UNKNOWN && it.isAlive && (term.all || key(it) == term.name) }
                Term(term, if (entities.isEmpty()) null else statistic, NO_MATERIALS, entities.toTypedArray())
            }
            else -> {
                val block = category.of == Vanilla.Category.Of.BLOCK
                val materials = Material.values().filter { !it.isLegacy && (if (block) it.isBlock else it.isItem) && (term.all || it.key.key == term.name) }
                Term(term, if (materials.isEmpty()) null else statistic, materials.toTypedArray(), NO_ENTITIES)
            }
        }
    }

    // The custom stat's constant, or null if this server doesn't have it.
    private fun custom(key: String): Statistic? = try {
        Statistic.valueOf(Vanilla.bukkitName(key)).takeIf { it.type == Statistic.Type.UNTYPED }
    } catch (e: IllegalArgumentException) {
        null
    }

    // A mob's vanilla name: its key where it has one (1.14 on, found by name so 1.13's API compiles), else its 1.13 name.
    @Suppress("DEPRECATION")
    private fun key(type: EntityType): String? {
        val keyed: Any = type
        return if (keyed is Keyed) keyed.key.key else type.getName()
    }

    override fun read(player: PlayerRef, stats: Catalog.Snapshot, quit: Boolean, placeholders: Boolean): Event.Sample? {
        val online = bukkit.getPlayer(player.javaUuid) ?: return null
        val raw = LongArray(stats.width) { Event.Sample.NOT_READ }
        val papi = if (placeholders) this.placeholders else null
        for (stat in stats.stats) {
            when (val source = stat.source) {
                is StatDef.Vanillas -> {
                    val prepared = source.prepared as? Prepared ?: continue
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

    private fun read(player: Player, term: Term): Long {
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

    /** After a reload: try placeholders again. */
    fun reset() {
        placeholderFailed = false
    }

    private companion object {
        val NO_MATERIALS = emptyArray<Material>()
        val NO_ENTITIES = emptyArray<EntityType>()

        fun textDisplays(): Boolean = has("org.bukkit.entity.TextDisplay")

        fun criteria(): Boolean = try {
            Class.forName("org.bukkit.scoreboard.Criteria").getMethod("statistic", Statistic::class.java)
            true
        } catch (e: ReflectiveOperationException) {
            false
        } catch (e: LinkageError) {
            false
        }

        fun has(name: String): Boolean = try {
            Class.forName(name)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
