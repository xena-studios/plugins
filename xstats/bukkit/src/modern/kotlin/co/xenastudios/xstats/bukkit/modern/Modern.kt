package co.xenastudios.xstats.bukkit.modern

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.scheduler.TICK
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xstats.BoardHost
import co.xenastudios.xstats.store.Store
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Statistic
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Leaderboards in the world as text displays (1.19.4 on), no hologram plugin. The text is set after
 * each save, on the thread of the region it stands in.
 *
 * Displays aren't saved with the world: each is made when its chunk loads and goes when it unloads,
 * so a board removed while its chunk was unloaded never leaves one behind. The chunk listener is
 * registered only while there's a board. Only servers with text displays load this class.
 */
internal class TextBoards(private val platform: BukkitPlatform) : BoardHost, Listener {
    private val server = platform.plugin.server
    private val key = NamespacedKey(platform.plugin, "board")
    private var listening = false

    @Volatile
    private var rows: List<Store.BoardRow> = emptyList()

    /** The text each board shows now, by id. */
    private val texts = ConcurrentHashMap<Int, String>()

    /** Boards with an update waiting for their region: one is enough. */
    private val waiting = ConcurrentHashMap.newKeySet<Int>()

    override fun spot(player: PlayerRef): Store.BoardRow? {
        val online = server.getPlayer(player.javaUuid) ?: return null
        // Just above where they stand, centred on the block.
        val at = online.location.block.location
        return Store.BoardRow(0, at.world!!.name, at.x + 0.5, at.y + 2.5, at.z + 0.5, 0f, "", "")
    }

    override fun hasWorld(world: String): Boolean = server.getWorld(world) != null

    override fun show(row: Store.BoardRow, text: Component) {
        texts[row.id] = platform.text.legacy.draw(text)
        if (!waiting.add(row.id)) return
        platform.scheduler.run(Where.At(where(row)), action = Show(this, row))
    }

    // On the region's thread: sets the text of the board's display, making it if it isn't there.
    private class Show(private val boards: TextBoards, private val row: Store.BoardRow) : (Task) -> Unit {
        override fun invoke(task: Task) {
            boards.waiting.remove(row.id)
            boards.place(row)
        }
    }

    private fun place(row: Store.BoardRow) {
        val world = server.getWorld(row.world) ?: return
        val location = org.bukkit.Location(world, row.x, row.y, row.z, row.yaw, 0f)
        if (!world.isChunkLoaded(floor(row.x).toInt() shr 4, floor(row.z).toInt() shr 4)) return
        val text = texts[row.id] ?: return
        val found = displays(location, row.id)
        if (found.isEmpty()) {
            val display = world.spawnEntity(location, EntityType.TEXT_DISPLAY) as TextDisplay
            display.isPersistent = false
            display.billboard = Display.Billboard.VERTICAL
            display.alignment = TextDisplay.TextAlignment.CENTER
            display.lineWidth = 300
            display.persistentDataContainer.set(key, PersistentDataType.INTEGER, row.id)
            @Suppress("DEPRECATION")
            display.text = text
            return
        }
        @Suppress("DEPRECATION")
        found[0].text = text
        // A second one, as from a reload: only one stays.
        for (extra in found.drop(1)) extra.remove()
    }

    override fun remove(row: Store.BoardRow) {
        texts.remove(row.id)
        platform.scheduler.run(Where.At(where(row)), action = Remove(this, row))
    }

    private class Remove(private val boards: TextBoards, private val row: Store.BoardRow) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val world = boards.server.getWorld(row.world) ?: return
            for (display in boards.displays(org.bukkit.Location(world, row.x, row.y, row.z), row.id)) display.remove()
        }
    }

    private fun displays(location: org.bukkit.Location, id: Int): List<TextDisplay> {
        val found = ArrayList<TextDisplay>()
        for (entity in location.world!!.getNearbyEntities(location, 0.5, 0.5, 0.5)) {
            if (entity !is TextDisplay) continue
            if (entity.persistentDataContainer.get(key, PersistentDataType.INTEGER) == id) found += entity
        }
        return found
    }

    @Synchronized
    override fun listen(rows: List<Store.BoardRow>) {
        this.rows = rows
        val wanted = rows.isNotEmpty()
        if (wanted && !listening) server.pluginManager.registerEvents(this, platform.plugin)
        else if (!wanted && listening) HandlerList.unregisterAll(this)
        listening = wanted
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        for (row in rows) {
            if (row.world == event.world.name && floor(row.x).toInt() shr 4 == event.chunk.x && floor(row.z).toInt() shr 4 == event.chunk.z) {
                // Next tick: entities are better not spawned while the chunk is still loading.
                platform.scheduler.run(Where.At(where(row)), delay = TICK, action = Show(this, row))
            }
        }
    }

    private fun where(row: Store.BoardRow): Location = Location(WorldRef.of(row.world), row.x, row.y, row.z)
}

/** `/xstats status`'s check of the stat table against Paper's scoreboard criteria, which name each stat by its vanilla key. */
internal object Criteria {
    fun verify(): List<String> {
        val wrong = ArrayList<String>()
        for (statistic in Statistic.values()) {
            if (statistic.type != Statistic.Type.UNTYPED) continue
            val name = org.bukkit.scoreboard.Criteria.statistic(statistic).name
            val prefix = "minecraft.custom:minecraft."
            if (!name.startsWith(prefix)) {
                wrong += "$statistic is $name"
                continue
            }
            val key = name.substring(prefix.length)
            if (co.xenastudios.xstats.stat.Vanilla.bukkitName(key) != statistic.name) wrong += "$key should be $statistic"
            if (key !in co.xenastudios.xstats.stat.Vanilla.CUSTOM) wrong += "$key isn't in xStats' table"
        }
        val categories = listOf(
            Triple(Statistic.MINE_BLOCK, "mined", "stone"), Triple(Statistic.CRAFT_ITEM, "crafted", "stone_pickaxe"),
            Triple(Statistic.KILL_ENTITY, "killed", "zombie"), Triple(Statistic.ENTITY_KILLED_BY, "killed_by", "zombie"),
        )
        for ((statistic, category, sample) in categories) {
            val name = when (statistic.type) {
                Statistic.Type.ENTITY -> org.bukkit.scoreboard.Criteria.statistic(statistic, EntityType.ZOMBIE).name
                Statistic.Type.BLOCK -> org.bukkit.scoreboard.Criteria.statistic(statistic, Material.STONE).name
                else -> org.bukkit.scoreboard.Criteria.statistic(statistic, Material.STONE_PICKAXE).name
            }
            if (name != "minecraft.$category:minecraft.$sample") wrong += "$category is $name"
        }
        return wrong
    }
}
