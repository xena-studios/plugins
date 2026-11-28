@file:JvmName("BukkitWorlds")

package co.xenastudios.core.bukkit.world

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.WorldHook
import co.xenastudios.core.common.world.WorldHooks
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.block.data.BlockData as BukkitData
import org.bukkit.event.EventPriority as BukkitPriority

/**
 * The world's changes on this server, for the plugin, at [priority] among other plugins
 * (DECISIONS.md, "The world hooks"): Bukkit's block, inventory and entity events from 1.13
 * (Spigot, Paper, Folia), each kind registered only while a listener wants it. A logger asks at
 * [EventPriority.MONITOR], a plugin that stops changes earlier. [WorldHooks.close] stops them,
 * as the platform's `close` does.
 *
 * Throws [UnsupportedOperationException] before 1.13, which has no block data.
 */
@JvmOverloads
public fun BukkitPlatform.world(priority: EventPriority = EventPriority.NORMAL): WorldHooks {
    if (!server.atLeast(1, 13)) throw UnsupportedOperationException("The world hooks need Minecraft 1.13 or newer; this is ${server.version}")
    return BukkitWorldHooks(plugin, priority, log).also(::closeWith)
}

/**
 * The hooks on Bukkit. Each [WorldHook] is one or a few Bukkit events, registered with
 * `registerEvent` (no reflection over methods) at the plugin's priority, on a listener object of
 * its own so it can be unregistered alone. Every handler reads Bukkit's cancelled flag into the
 * event and writes the listeners' decision back, where the change can be stopped.
 */
internal class BukkitWorldHooks(
    internal val plugin: Plugin,
    priority: EventPriority,
    log: Log,
) : WorldHooks(priority, log) {

    override val supported: Set<WorldHook> = WorldHook.entries.toSet()

    internal val bukkitPriority: BukkitPriority = BukkitPriority.valueOf(priority.name)

    private val players = ConcurrentHashMap<UUID, PlayerRef>()
    private val worlds = ConcurrentHashMap<String, WorldRef>()
    private val started = HashMap<WorldHook, Listener>()

    // Who broke what in the last second, so a torch that falls off is theirs: only while a
    // player's or an explosion's changes are heard, on servers that say what falls (Paper).
    @Volatile
    internal var recent: RecentBreaks? = null
    private var falling: Listener? = null

    private val quits = object : Listener {}

    init {
        plugin.server.pluginManager.registerEvent(PlayerQuitEvent::class.java, quits, BukkitPriority.MONITOR, EventExecutor { _, event ->
            if (event is PlayerQuitEvent) players.remove(event.player.uniqueId)
        }, plugin, false)
    }

    override fun start(hook: WorldHook) {
        val listener: Listener = when (hook) {
            WorldHook.BLOCK_PLAYER -> PlayerBlocks(this)
            WorldHook.BLOCK_EXPLOSION -> Explosions(this)
            WorldHook.BLOCK_FIRE -> Burns(this)
            WorldHook.BLOCK_PISTON -> Pistons(this)
            WorldHook.BLOCK_FLUID -> Flows(this)
            WorldHook.BLOCK_ENTITY -> EntityBlocks(this, players = false)
            WorldHook.BLOCK_DECAY -> Decays(this)
            WorldHook.CONTAINER -> Containers(this)
            WorldHook.CONTAINER_HOPPER -> Hoppers(this)
            WorldHook.ENTITY_SPAWN -> Spawns(this)
            WorldHook.ENTITY_DEATH -> Deaths(this)
            WorldHook.ENTITY_CHANGE -> Tames(this)
        }
        started[hook] = listener
        updateFalling()
    }

    override fun stop(hook: WorldHook) {
        started.remove(hook)?.let(HandlerList::unregisterAll)
        updateFalling()
    }

    override fun close() {
        super.close()
        HandlerList.unregisterAll(quits)
        players.clear()
    }

    // Paper's BlockDestroyEvent (blocks that fall off another), heard while a player's or an
    // explosion's changes are: they're put down to whoever broke the block beside them.
    private fun updateFalling() {
        val wanted = WorldHook.BLOCK_PLAYER in started || WorldHook.BLOCK_EXPLOSION in started
        if (wanted && falling == null) {
            val listener = FallingBlocks.register(this) ?: return
            recent = RecentBreaks()
            falling = listener
        } else if (!wanted && falling != null) {
            HandlerList.unregisterAll(falling!!)
            falling = null
            recent = null
        }
    }

    /** Registers [handle] for [type] events on [listener], at the plugin's priority, cancelled ones included. */
    internal fun <T : Event> on(listener: Listener, type: Class<T>, priority: BukkitPriority = bukkitPriority, handle: (T) -> Unit) {
        plugin.server.pluginManager.registerEvent(type, listener, priority, EventExecutor { _, event ->
            if (type.isInstance(event)) handle(type.cast(event))
        }, plugin, false)
    }

    internal fun ref(player: Player): PlayerRef = players.computeIfAbsent(player.uniqueId, MAKE_REF)

    internal fun ref(world: World): WorldRef = worlds.computeIfAbsent(world.name, MAKE_WORLD)

    internal fun heard(cause: ChangeCause): Boolean = hears(cause)

    internal fun heard(hook: WorldHook): Boolean = hears(hook)

    internal fun tell(event: BlockChange): BlockChange = fire(event)

    internal fun tell(event: co.xenastudios.core.common.world.ContainerChange) {
        fire(event)
    }

    internal fun tell(event: co.xenastudios.core.common.world.EntitySpawn): co.xenastudios.core.common.world.EntitySpawn = fire(event)

    internal fun tell(event: co.xenastudios.core.common.world.EntityDeath) {
        fire(event)
    }

    internal fun tell(event: co.xenastudios.core.common.world.EntityChange): co.xenastudios.core.common.world.EntityChange = fire(event)

    /** A change at [block] from [before] to [after], made into an event and heard. */
    internal fun change(
        block: Block,
        before: BukkitData,
        after: BukkitData,
        cause: ChangeCause,
        player: Player?,
        source: String?,
        cancelled: Boolean,
        cancellable: Boolean = true,
    ): BlockChange {
        val world = block.world
        val event = BlockChange(
            ref(world), block.x, block.y, block.z, BukkitBlock(before), BukkitBlock(after), cause,
            player?.let(::ref), player?.name, if (player == null) source else null, cancellable, world,
        )
        if (cancelled) event.isCancelled = true
        return fire(event)
    }

    private companion object {
        val MAKE_REF: java.util.function.Function<UUID, PlayerRef> = java.util.function.Function { it.toPlayerRef() }
        val MAKE_WORLD: java.util.function.Function<String, WorldRef> = java.util.function.Function { WorldRef.of(it) }
    }
}

/** Ids of Bukkit's types, worked out once per type. */
internal object Keys {
    private val materials = arrayOfNulls<String>(Material.values().size)
    private val entities = arrayOfNulls<String>(org.bukkit.entity.EntityType.values().size)

    fun of(material: Material): String = materials[material.ordinal] ?: material.key.toString().also { materials[material.ordinal] = it }

    fun of(type: org.bukkit.entity.EntityType): String = entities[type.ordinal] ?: entityKey(type).also { entities[type.ordinal] = it }

    // EntityType is Keyed from 1.14; on 1.13 its savegame name is the id's path.
    @Suppress("DEPRECATION")
    private fun entityKey(type: org.bukkit.entity.EntityType): String {
        val keyed = (type as Any) as? org.bukkit.Keyed
        if (keyed != null) return keyed.key.toString()
        // getName(), the savegame id, not the enum constant's name (which Kotlin's .name would be).
        val id: String? = type.getName()
        return "minecraft:" + (id ?: type.toString().lowercase())
    }

    fun isAir(material: Material): Boolean = material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR
}

/** A block state on Bukkit: its type's id at once, its text only when read. */
internal class BukkitBlock(override val native: BukkitData) : co.xenastudios.core.common.world.BlockData() {
    override val type: String get() = Keys.of(native.material)
    override val data: String by lazy(LazyThreadSafetyMode.NONE) { native.asString }
    override val isAir: Boolean get() = Keys.isAir(native.material)
}

/** An item on Bukkit: a copy of one of it. */
internal class BukkitItem(override val native: org.bukkit.inventory.ItemStack) : co.xenastudios.core.common.world.ItemData() {
    override val type: String get() = Keys.of(native.type)
}
