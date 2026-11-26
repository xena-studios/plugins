package co.xenastudios.xteleport.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.BukkitServer
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.toBukkit
import co.xenastudios.core.bukkit.toCore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xteleport.BlockKind
import co.xenastudios.xteleport.Blocks
import co.xenastudios.xteleport.Economy
import co.xenastudios.xteleport.Features
import co.xenastudios.xteleport.PlayerEvents
import co.xenastudios.xteleport.SpawnEvents
import co.xenastudios.xteleport.TeleportServer
import co.xenastudios.xteleport.WorldInfo
import co.xenastudios.xteleport.XMoneyEconomy
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.Volatile

/**
 * What xTeleport does on a Bukkit-family server, from 1.8.8 to the newest Paper and Folia.
 * Compiled against Spigot 1.8.8's API; what newer servers add is in `modern`, used only where
 * [Paths] found it at startup: Paper's chunk loads and teleports off the server thread, block data
 * for waterlogged blocks, heightmaps, keyed biomes, action bars and the async spawn placement.
 * Without them (Spigot, CraftBukkit) chunks load on the server thread, at most one not yet loaded a
 * tick, and players are teleported there.
 */
internal class BukkitTeleportServer(private val plugin: Plugin, private val platform: BukkitPlatform) : TeleportServer {
    private val bukkit = plugin.server
    private val paths = Paths.detect(platform.server)
    private val kinds = Kinds()

    override val features: Features = Features(
        actionBar = paths.adventureBar || paths.spigotBar,
        loading = if (paths.asyncChunks) "in the background (Paper)" else "on the server thread, one chunk a tick",
        spawnOnJoin = paths.asyncSpawn || paths.spigotSpawn,
    )

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun online(): List<PlayerRef> = bukkit.onlinePlayers.map { it.ref }

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        val p = player(player) ?: return
        val legacy = platform.text.legacy.draw(message)
        when {
            paths.adventureBar -> co.xenastudios.xteleport.bukkit.modern.AdventureBar.send(p, legacy)
            paths.spigotBar -> co.xenastudios.xteleport.bukkit.modern.SpigotBar.send(p, legacy)
        }
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true

    override fun homeNumbers(player: PlayerRef): Int {
        val p = player(player) ?: return 0
        var best = 0
        for (info in p.effectivePermissions) {
            val node = info.permission
            if (info.value && node.startsWith(NUMBERED)) best = maxOf(best, number(node.substring(NUMBERED.length)))
        }
        return best
    }

    override fun location(player: PlayerRef): Location? = player(player)?.location?.takeIf { it.world != null }?.toCore()

    override fun bed(player: PlayerRef): Location? {
        val p = player(player) ?: return null
        val at = when {
            paths.potentialRespawn -> co.xenastudios.xteleport.bukkit.modern.potentialRespawn(p)
            paths.potentialBed -> co.xenastudios.xteleport.bukkit.modern.potentialBed(p)
            // Before Paper 1.14 the server's own, which checks the bed is still there.
            else -> p.bedSpawnLocation
        }
        return at?.takeIf { it.world != null }?.toCore()
    }

    override fun standOn(block: Location): Location? {
        val world = block.world.loaded() ?: return null
        val at = world.getBlockAt(block.blockX, block.blockY, block.blockZ)
        val name = at.type.name
        val bed = name.endsWith("_BED") || name == "BED_BLOCK"
        if (!bed && name != "RESPAWN_ANCHOR") {
            // Before Paper 1.14 the server gave where to stand already, beside the bed.
            return if (paths.potentialRespawn || paths.potentialBed) null else block
        }
        // A bed is just over half a block high; an anchor is a full block.
        return Location(block.world, at.x + 0.5, at.y + if (bed) 0.5625 else 1.0, at.z + 0.5)
    }

    private fun WorldRef.loaded(): World? = bukkit.getWorld(name)

    override fun worlds(): List<WorldRef> = bukkit.worlds.map { it.ref }

    override fun world(name: String): WorldInfo? {
        val world = bukkit.getWorld(name) ?: return null
        val border = world.worldBorder
        val center = border.center
        val min = if (paths.heights) co.xenastudios.xteleport.bukkit.modern.minHeight(world) else 0
        val ceiling = if (paths.heights) co.xenastudios.xteleport.bukkit.modern.hasCeiling(world) else world.environment == World.Environment.NETHER
        val logical = if (paths.heights) co.xenastudios.xteleport.bukkit.modern.logicalHeight(world) else if (ceiling) 128 else world.maxHeight
        return WorldInfo(
            world.ref, min, world.maxHeight, logical, ceiling, world.environment == World.Environment.THE_END,
            world.spawnLocation.toCore(), center.x, center.z, border.size,
        )
    }

    override fun loadChunk(world: WorldRef, chunkX: Int, chunkZ: Int, generate: Boolean): Pending<Boolean> {
        val loaded = world.loaded() ?: return pendingOf(false)
        if (paths.asyncChunks) {
            return try {
                co.xenastudios.xteleport.bukkit.modern.chunkAsync(loaded, chunkX, chunkZ, generate)
            } catch (e: Exception) {
                pendingOf(false)
            }
        }
        return chunks.load(loaded, chunkX, chunkZ, generate)
    }

    private val chunks = SyncChunks()

    /**
     * Chunk loads on a server without Paper's: on the main thread, every chunk already loaded at
     * once, and at most one that isn't a tick, so an `/rtp` crowd can't stall the server. The task
     * runs only while there are chunks waiting.
     */
    private inner class SyncChunks : (Task) -> Unit {
        private inner class Load(val world: World, val x: Int, val z: Int, val generate: Boolean, val done: Pending<Boolean>)

        private val waiting = ConcurrentLinkedQueue<Load>()

        @Volatile
        private var task: Task? = null
        private val lock = Any()

        fun load(world: World, x: Int, z: Int, generate: Boolean): Pending<Boolean> {
            val done = newPending<Boolean>()
            waiting += Load(world, x, z, generate, done)
            co.xenastudios.core.common.locked(lock) {
                if (task == null) task = platform.scheduler.run(Where.Global, period = co.xenastudios.core.common.scheduler.TICK, action = this)
            }
            return done
        }

        override fun invoke(tick: Task) {
            var loadedOne = false
            while (true) {
                val next = waiting.peek() ?: break
                val already = next.world.isChunkLoaded(next.x, next.z)
                if (!already && loadedOne) break
                waiting.poll()
                val ok = already || try {
                    next.world.loadChunk(next.x, next.z, next.generate)
                } catch (e: Exception) {
                    false
                }
                if (!already) loadedOne = true
                next.done.resolve(ok)
            }
            co.xenastudios.core.common.locked(lock) {
                if (waiting.isEmpty()) {
                    tick.cancel()
                    task = null
                }
            }
        }
    }

    override fun blocks(world: WorldRef): Blocks? {
        val loaded = world.loaded() ?: return null
        return WorldBlocks(loaded)
    }

    private inner class WorldBlocks(private val world: World) : Blocks {
        private val minY = if (paths.heights) co.xenastudios.xteleport.bukkit.modern.minHeight(world) else 0
        private val maxY = world.maxHeight

        override fun kind(x: Int, y: Int, z: Int): Int {
            if (y < minY || y >= maxY) return BlockKind.AIR
            val block = world.getBlockAt(x, y, z)
            val type = block.type
            var kind = kinds.of(type)
            if (paths.blockData && kinds.waterloggable(type) && co.xenastudios.xteleport.bukkit.modern.waterlogged(block)) kind = kind or BlockKind.WATER
            return kind
        }

        override fun highest(x: Int, z: Int): Int {
            if (paths.heightMaps) {
                val y = co.xenastudios.xteleport.bukkit.modern.highest(world, x, z)
                // An empty column (the End's void) answers its floor, with air.
                return if (y < minY || (y == minY && world.getBlockAt(x, y, z).type.name.endsWith("AIR"))) Int.MIN_VALUE else y
            }
            // Before 1.15: the first block above the highest that blocks light, then down past air.
            var y = minOf(world.getHighestBlockYAt(x, z), maxY - 1)
            while (y >= minY && kind(x, y, z) == BlockKind.AIR) y--
            return if (y < minY) Int.MIN_VALUE else y
        }

        @Suppress("DEPRECATION")
        override fun biome(x: Int, y: Int, z: Int): String =
            if (paths.biomes3d) co.xenastudios.xteleport.bukkit.modern.biome(world, x, y, z) else world.getBiome(x, z).name.lowercase()
    }

    override fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit) {
        val p = player(player) ?: return done(false)
        val target = to.toBukkit(bukkit) ?: return done(false)
        if (paths.asyncTeleport) {
            co.xenastudios.xteleport.bukkit.modern.teleportAsync(p, target) { moved ->
                platform.scheduler.run(Where.on(player), retired = { done(false) }) { done(moved) }
            }
        } else {
            done(p.teleport(target, TeleportCause.COMMAND))
        }
    }

    override fun watchPlayers(listener: PlayerEvents): AutoCloseable {
        val bukkitListener = object : Listener {
            @EventHandler(priority = EventPriority.MONITOR)
            fun onJoin(event: PlayerJoinEvent) {
                listener.joined(event.player.ref, event.player.name)
            }

            // Last, after any other plugin's quit handling.
            @EventHandler(priority = EventPriority.MONITOR)
            fun onQuit(event: PlayerQuitEvent) {
                listener.quit(event.player.ref)
            }
        }
        bukkit.pluginManager.registerEvents(bukkitListener, plugin)
        return AutoCloseable { HandlerList.unregisterAll(bukkitListener) }
    }

    override fun watchSpawn(listener: SpawnEvents): AutoCloseable {
        val listeners = ArrayList<Listener>()
        val respawn = object : Listener {
            @EventHandler(priority = EventPriority.HIGH)
            fun onRespawn(event: PlayerRespawnEvent) {
                // Deaths only: leaving the End goes where the game sends it (where the server says which).
                if (paths.respawnReason && !co.xenastudios.xteleport.bukkit.modern.deathRespawn(event)) return
                if (event.isBedSpawn || (paths.anchors && co.xenastudios.xteleport.bukkit.modern.anchorSpawn(event))) return
                listener.respawn(event.player.ref)?.toBukkit(bukkit)?.let { event.respawnLocation = it }
            }
        }
        listeners += respawn
        when {
            paths.asyncSpawn -> listeners += co.xenastudios.xteleport.bukkit.modern.AsyncSpawn { id, first ->
                listener.spawnOnJoin(PlayerRef.parseOrNull(id.toString())!!, first)?.toBukkit(bukkit)
            }
            paths.spigotSpawn -> listeners += object : Listener {
                @EventHandler(priority = EventPriority.HIGH)
                fun onSpawn(event: org.spigotmc.event.player.PlayerSpawnLocationEvent) {
                    listener.spawnOnJoin(event.player.ref, !event.player.hasPlayedBefore())?.toBukkit(bukkit)?.let { event.spawnLocation = it }
                }
            }
            // CraftBukkit: a teleport just after joining.
            else -> listeners += object : Listener {
                @EventHandler(priority = EventPriority.HIGH)
                fun onJoin(event: PlayerJoinEvent) {
                    val p = event.player
                    val to = listener.spawnOnJoin(p.ref, !p.hasPlayedBefore())?.toBukkit(bukkit) ?: return
                    platform.scheduler.run(Where.on(p.ref)) { p.teleport(to, TeleportCause.PLUGIN) }
                }
            }
        }
        for (l in listeners) bukkit.pluginManager.registerEvents(l, plugin)
        return AutoCloseable { for (l in listeners) HandlerList.unregisterAll(l) }
    }

    override fun watchDamage(hurt: (PlayerRef) -> Unit): AutoCloseable {
        val bukkitListener = object : Listener {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            fun onDamage(event: EntityDamageEvent) {
                val p = event.entity as? Player ?: return
                hurt(p.ref)
            }
        }
        bukkit.pluginManager.registerEvents(bukkitListener, plugin)
        return AutoCloseable { HandlerList.unregisterAll(bukkitListener) }
    }

    // The economy, found as a cost is first needed, and again if its plugin goes away.
    @Volatile
    private var economy: Pair<Plugin, Economy>? = null

    @Volatile
    private var lookedAt = 0L

    override fun economy(): Economy? {
        economy?.let { (owner, found) -> if (owner.isEnabled) return found }
        val now = System.currentTimeMillis()
        // Not found a moment ago: ask again at most once a second.
        if (economy == null && now - lookedAt < 1000) return null
        lookedAt = now
        val manager = bukkit.pluginManager
        val xmoney = manager.getPlugin("xMoney")?.takeIf { it.isEnabled }
        val found: Pair<Plugin, Economy>? = xmoney?.let { owner -> XMoneyEconomy.find(owner.javaClass.classLoader)?.let { owner to it } }
            ?: VaultEconomy.find(bukkit)
        economy = found
        return found?.second
    }

    /** Which way to do each thing here, found once from the server's classes. */
    internal class Paths(
        val asyncChunks: Boolean,
        val asyncTeleport: Boolean,
        val blockData: Boolean,
        val heightMaps: Boolean,
        val heights: Boolean,
        val biomes3d: Boolean,
        val adventureBar: Boolean,
        val spigotBar: Boolean,
        val potentialRespawn: Boolean,
        val potentialBed: Boolean,
        val anchors: Boolean,
        val respawnReason: Boolean,
        val asyncSpawn: Boolean,
        val spigotSpawn: Boolean,
    ) {
        companion object {
            fun detect(server: BukkitServer): Paths {
                val loader = Player::class.java.classLoader
                fun exists(type: String): Boolean = try {
                    Class.forName(type, false, loader)
                    true
                } catch (e: ClassNotFoundException) {
                    false
                }
                fun has(type: String, method: String, vararg parameters: Class<*>): Boolean = try {
                    Class.forName(type, false, loader).getMethod(method, *parameters)
                    true
                } catch (e: ReflectiveOperationException) {
                    false
                } catch (e: LinkageError) {
                    false
                }
                val int = Int::class.javaPrimitiveType!!
                val boolean = Boolean::class.javaPrimitiveType!!
                return Paths(
                    asyncChunks = has("org.bukkit.World", "getChunkAtAsync", int, int, boolean),
                    asyncTeleport = has("org.bukkit.entity.Entity", "teleportAsync", org.bukkit.Location::class.java, TeleportCause::class.java),
                    blockData = exists("org.bukkit.block.data.Waterlogged"),
                    heightMaps = exists("org.bukkit.HeightMap"),
                    heights = has("org.bukkit.generator.WorldInfo", "getMinHeight") && has("org.bukkit.World", "getLogicalHeight"),
                    biomes3d = has("org.bukkit.World", "getBiome", int, int, int) && has("org.bukkit.Keyed", "getKey") && server.atLeast(1, 16),
                    adventureBar = server.adventure && exists("net.kyori.adventure.text.Component") &&
                        has("org.bukkit.entity.Player", "sendActionBar", Class.forName("net.kyori.adventure.text.Component", false, loader)),
                    // 1.8.8 ships Bungee's ChatMessageType, but its Player.Spigot can't send one.
                    spigotBar = exists("net.md_5.bungee.api.ChatMessageType") && try {
                        Class.forName("org.bukkit.entity.Player\$Spigot", false, loader).getMethod(
                            "sendMessage", Class.forName("net.md_5.bungee.api.ChatMessageType", false, loader),
                            java.lang.reflect.Array.newInstance(Class.forName("net.md_5.bungee.api.chat.BaseComponent", false, loader), 0).javaClass,
                        )
                        true
                    } catch (e: ReflectiveOperationException) {
                        false
                    },
                    potentialRespawn = has("org.bukkit.entity.Player", "getPotentialRespawnLocation"),
                    potentialBed = has("org.bukkit.entity.Player", "getPotentialBedLocation"),
                    anchors = has("org.bukkit.event.player.PlayerRespawnEvent", "isAnchorSpawn"),
                    respawnReason = has("org.bukkit.event.player.PlayerRespawnEvent", "getRespawnReason"),
                    asyncSpawn = exists("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent"),
                    spigotSpawn = exists("org.spigotmc.event.player.PlayerSpawnLocationEvent"),
                )
            }
        }
    }

    private companion object {
        const val NUMBERED = "xteleport.homes."

        // xteleport.homes.5 -> 5; anything else (others, unlimited) -> 0.
        fun number(suffix: String): Int =
            if (suffix.isEmpty() || suffix.length > 6 || !suffix.all { it in '0'..'9' }) 0 else suffix.toInt()
    }
}

/**
 * Each block type's [BlockKind], worked out once per type from its name and Bukkit's flags: the
 * names cover 1.8.8's materials (`STATIONARY_LAVA`, `LEAVES_2`, `SOIL`) and today's.
 */
internal class Kinds {
    private val known = IntArray(Material.values().size) { -1 }
    private val wet = ByteArray(Material.values().size) { -1 }

    fun of(type: Material): Int {
        val i = type.ordinal
        val cached = known[i]
        if (cached >= 0) return cached
        return work(type).also { known[i] = it }
    }

    fun waterloggable(type: Material): Boolean {
        val i = type.ordinal
        if (wet[i] < 0) wet[i] = if (co.xenastudios.xteleport.bukkit.modern.waterloggable(type)) 1 else 0
        return wet[i] == 1.toByte()
    }

    @Suppress("DEPRECATION")
    private fun work(type: Material): Int {
        val name = type.name
        var kind = 0
        if (type.isSolid) kind = kind or BlockKind.SOLID
        if (type.isOccluding) kind = kind or BlockKind.FULL
        if (name in HURTS) kind = kind or BlockKind.HURTS or (if ("LAVA" in name) BlockKind.LIQUID else 0)
        if (name in WATER) kind = kind or BlockKind.WATER or (if ("WATER" in name || name == "BUBBLE_COLUMN") BlockKind.LIQUID else 0)
        if (name in CLIMBABLE) kind = kind or BlockKind.CLIMBABLE
        if (name.endsWith("LEAVES") || name == "LEAVES_2") kind = kind or BlockKind.LEAVES
        if (name == "BEDROCK") kind = kind or BlockKind.BEDROCK
        if (name == "SOUL_SAND" || name == "MUD") kind = kind or BlockKind.SHORT
        if (name == "FARMLAND" || name == "SOIL" || name == "DIRT_PATH" || name == "GRASS_PATH") kind = kind or BlockKind.SHORTER
        return kind
    }

    private companion object {
        val HURTS = setOf(
            "LAVA", "STATIONARY_LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "MAGMA", "CAMPFIRE", "SOUL_CAMPFIRE", "CACTUS",
            "SWEET_BERRY_BUSH", "WITHER_ROSE", "POWDER_SNOW", "POINTED_DRIPSTONE",
        )
        val WATER = setOf("WATER", "STATIONARY_WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS")
        val CLIMBABLE = setOf(
            "LADDER", "VINE", "SCAFFOLDING", "WEEPING_VINES", "WEEPING_VINES_PLANT", "TWISTING_VINES", "TWISTING_VINES_PLANT",
            "CAVE_VINES", "CAVE_VINES_PLANT",
        )
    }
}
