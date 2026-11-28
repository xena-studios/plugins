package co.xenastudios.xstats.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.scheduler.TICK
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.level
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.xstats.BoardHost
import co.xenastudios.xstats.store.Store
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import net.minecraft.network.chat.Component as McComponent

/**
 * Leaderboards in the world on Fabric, as Minecraft's text displays (every line here has them), no
 * hologram mod: Bukkit's TextBoards on Minecraft's own entities. The text is set after each save, on
 * the server thread.
 *
 * Minecraft saves entities with their chunk and has no "don't save this" for a display, so each
 * board's display carries its id in its (hidden) custom name, `xstats-board:<id>`: one is reused
 * when its chunk loads again, and one whose board is gone (removed while its chunk was unloaded) is
 * discarded as it loads. Its text and facing are set through the entity's synced data, found once
 * by type ([TextData]): their setters are private, under other names on each line.
 *
 * A board's world is its dimension's id (`minecraft:overworld`), or for Minecraft's own, the id's
 * path (`overworld`), which a command can type: Brigadier's words can't hold a `:`.
 */
internal class FabricBoards(private val platform: FabricPlatform) : BoardHost {
    @Volatile
    private var rows: List<Store.BoardRow> = emptyList()

    /** Whether [listen] has been told the boards yet: until then, no display is thought orphaned. */
    @Volatile
    private var known = false

    private var registered = false

    /** The text each board shows now, by id. */
    private val texts = ConcurrentHashMap<Int, McComponent>()

    /** Boards with an update waiting for the server thread: one is enough. */
    private val waiting = ConcurrentHashMap.newKeySet<Int>()

    override fun spot(player: PlayerRef): Store.BoardRow? {
        val online = platform.server?.player(player) ?: return null
        val at = online.blockPosition()
        return Store.BoardRow(0, name(online.level().ref), at.x + 0.5, at.y + 2.5, at.z + 0.5, 0f, "", "")
    }

    override fun hasWorld(world: String): Boolean = level(world) != null

    override fun show(row: Store.BoardRow, text: Component) {
        texts[row.id] = MinecraftRenderer.draw(text)
        if (!waiting.add(row.id)) return
        platform.scheduler.run(Where.Global, action = Show(this, row))
    }

    // On the server thread: sets the text of the board's display, making it if it isn't there.
    private class Show(private val boards: FabricBoards, private val row: Store.BoardRow) : (Task) -> Unit {
        override fun invoke(task: Task) {
            boards.waiting.remove(row.id)
            boards.place(row)
        }
    }

    private fun place(row: Store.BoardRow) {
        val level = level(row.world) ?: return
        if (!level.chunkSource.hasChunk(floor(row.x).toInt() shr 4, floor(row.z).toInt() shr 4)) return
        val text = texts[row.id] ?: return
        val found = displays(level, row)
        if (found.isEmpty()) {
            val display = Display.TextDisplay(TEXT_DISPLAY, level)
            display.setPos(row.x, row.y, row.z)
            display.setYRot(row.yaw)
            display.customName = McComponent.literal(PREFIX + row.id)
            display.isCustomNameVisible = false
            val data = data(display)
            data.billboardVertical(display)
            data.text(display, text)
            level.addFreshEntity(display)
            return
        }
        data(found[0]).text(found[0], text)
        // A second one, as from a crash before a removal was saved: only one stays.
        for (extra in found.drop(1)) extra.discard()
    }

    override fun remove(row: Store.BoardRow) {
        texts.remove(row.id)
        platform.scheduler.run(Where.Global, action = Remove(this, row))
    }

    private class Remove(private val boards: FabricBoards, private val row: Store.BoardRow) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val level = boards.level(row.world) ?: return
            for (display in boards.displays(level, row)) display.discard()
        }
    }

    private fun displays(level: ServerLevel, row: Store.BoardRow): List<Display.TextDisplay> =
        level.getEntities(TEXT_DISPLAY, AABB.ofSize(Vec3(row.x, row.y, row.z), 1.0, 1.0, 1.0)) { board(it) == row.id }

    @Synchronized
    override fun listen(rows: List<Store.BoardRow>) {
        this.rows = rows
        val first = !known
        known = true
        if (!registered && rows.isNotEmpty()) {
            // Fabric's events can't be unregistered: once registered, they do nothing without boards.
            registered = true
            ServerChunkEvents.CHUNK_LOAD.register(ChunkLoaded(this))
            ServerEntityEvents.ENTITY_LOAD.register(EntityLoaded(this))
        }
        // Displays loaded before the boards were read (the spawn chunks): any whose board is gone.
        if (first) platform.scheduler.run(Where.Global, action = Sweep(this))
    }

    private class Sweep(private val boards: FabricBoards) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val server = boards.platform.server ?: return
            for (level in server.allLevels) {
                for (entity in level.allEntities) if (entity is Display.TextDisplay) boards.orphan(entity)
            }
        }
    }

    private class ChunkLoaded(private val boards: FabricBoards) : ServerChunkEvents.Load {
        //? if >=26.1 {
        override fun onChunkLoad(level: ServerLevel, chunk: net.minecraft.world.level.chunk.LevelChunk, generated: Boolean) {
        //?} else
        //override fun onChunkLoad(level: ServerLevel, chunk: net.minecraft.world.level.chunk.LevelChunk) {
            val rows = boards.rows
            if (rows.isEmpty()) return
            val world = boards.name(level.ref)
            for (row in rows) {
                if (boards.sameWorld(row.world, world) && floor(row.x).toInt() shr 4 == chunk.pos.x && floor(row.z).toInt() shr 4 == chunk.pos.z) {
                    // Next tick: entities are better not added while the chunk is still loading.
                    boards.platform.scheduler.run(Where.Global, delay = TICK, action = Show(boards, row))
                }
            }
        }
    }

    private class EntityLoaded(private val boards: FabricBoards) : ServerEntityEvents.Load {
        override fun onLoad(entity: Entity, level: ServerLevel) {
            if (entity is Display.TextDisplay) boards.orphan(entity)
        }
    }

    // A board's display whose board is gone goes too.
    private fun orphan(display: Display.TextDisplay) {
        val id = board(display) ?: return
        if (known && rows.none { it.id == id }) display.discard()
    }

    private fun board(entity: Entity): Int? {
        val name = entity.customName?.string ?: return null
        return if (name.startsWith(PREFIX)) name.substring(PREFIX.length).toIntOrNull() else null
    }

    /** The level a board names: its dimension's id, or `overworld` for `minecraft:overworld`. */
    fun level(world: String): ServerLevel? {
        val server = platform.server ?: return null
        return server.level(WorldRef.of(world)) ?: if (':' in world) null else server.level(WorldRef.of("minecraft:$world"))
    }

    private fun sameWorld(a: String, b: String): Boolean = full(a) == full(b)

    private fun full(world: String): String = if (':' in world) world else "minecraft:$world"

    /** What a board stores for [world]: Minecraft's own by the path of their id, which can be typed. */
    fun name(world: WorldRef): String = world.name.removePrefix("minecraft:")

    /**
     * The text display's synced text and the display's billboard, found by what they hold: their
     * fields and setters are private, and named differently on each line once Minecraft is remapped.
     * The text is the display's only Component; the billboard is Display's only byte.
     */
    private class TextData(probe: Display.TextDisplay) {
        private val text: EntityDataAccessor<McComponent>
        private val billboard: EntityDataAccessor<Byte>

        init {
            @Suppress("UNCHECKED_CAST")
            text = accessors(Display.TextDisplay::class.java).single { probe.entityData.get(it) is McComponent } as EntityDataAccessor<McComponent>
            @Suppress("UNCHECKED_CAST")
            billboard = accessors(Display::class.java).single { probe.entityData.get(it) is Byte } as EntityDataAccessor<Byte>
        }

        private fun accessors(type: Class<*>): List<EntityDataAccessor<*>> = type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && EntityDataAccessor::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(null) as EntityDataAccessor<*> }

        fun text(display: Display.TextDisplay, value: McComponent) {
            display.entityData.set(text, value)
        }

        // Display.BillboardConstraints.VERTICAL: it turns to face whoever looks, upright.
        fun billboardVertical(display: Display.TextDisplay) {
            display.entityData.set(billboard, 1.toByte())
        }
    }

    @Volatile
    private var madeData: TextData? = null

    // Found the first time a board is shown, from a display that's been made (its data defined).
    private fun data(display: Display.TextDisplay): TextData = madeData ?: TextData(display).also { madeData = it }

    private companion object {
        const val PREFIX = "xstats-board:"

        // The text display's type: EntityType's constants moved to EntityTypes in 26.1.
        //? if >=26.1 {
        val TEXT_DISPLAY: EntityType<Display.TextDisplay> get() = net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY
        //?} else
        //val TEXT_DISPLAY: EntityType<Display.TextDisplay> get() = EntityType.TEXT_DISPLAY
    }
}
