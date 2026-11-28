package co.xenastudios.xplots.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.toBukkit
import co.xenastudios.core.bukkit.toCore
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menus
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xplots.ItemCurrency
import co.xenastudios.xplots.Known
import co.xenastudios.xplots.PlotServer
import co.xenastudios.xplots.api.RegionBackend
import co.xenastudios.xplots.model.Box
import co.xenastudios.xplots.model.Facing
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Rotatable
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Boat
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Hanging
import org.bukkit.entity.Item
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.Plugin

/**
 * xPlots' [PlotServer] on a Bukkit-family server from 1.13 to the newest Paper and Folia: players
 * here, everything else (blocks, signs, items, menus' icons, WorldGuard and WorldEdit) in
 * [BukkitWorld], made the first time one is needed. What Paper adds is found once ([Paths]).
 */
internal class BukkitPlotServer(private val plugin: Plugin, private val platform: BukkitPlatform, private val cache: PlayerCache) : PlotServer {
    private val bukkit = plugin.server
    val paths: Paths = Paths.detect()

    @Volatile
    private var made: BukkitWorld? = null
    private val world: BukkitWorld get() = made ?: synchronized(this) { made ?: BukkitWorld(plugin, platform, cache, paths).also { made = it } }

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    override fun online(): List<PlayerRef> = bukkit.onlinePlayers.map { it.ref }
    override fun isOnline(player: PlayerRef): Boolean = player(player) != null
    override fun name(player: PlayerRef): String? = player(player)?.name ?: cache.get(player)?.name

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun actionBar(player: PlayerRef, message: Component) = world.actionBar(player, message)
    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true
    override fun highestNumbered(player: PlayerRef, prefix: String): Int = world.highestNumbered(player, prefix)

    override fun position(player: PlayerRef): Location? {
        val at = player(player)?.location ?: return null
        if (at.world == null) return null
        return at.toCore()
    }

    override fun known(name: String): Known? = world.known(name)
    override fun teleport(player: PlayerRef, to: Location) = world.teleport(player, to)
    override fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean = world.canSee(viewer, target)
    override fun hasWorld(world: String): Boolean = bukkit.getWorld(world) != null
    override fun worlds(): List<String> = bukkit.worlds.map { it.name }
    override fun isLoaded(world: String, chunkX: Int, chunkZ: Int): Boolean = bukkit.getWorld(world)?.isChunkLoaded(chunkX, chunkZ) == true
    override fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean> = this.world.load(world, chunkX, chunkZ)
    override fun release(world: String, chunkX: Int, chunkZ: Int) = this.world.release(world, chunkX, chunkZ)
    override fun stateAt(world: String, x: Int, y: Int, z: Int): String = this.world.stateAt(world, x, y, z)
    override fun compile(state: String): Any? = world.compile(state)
    override fun matches(world: String, x: Int, y: Int, z: Int, compiled: Any): Boolean = this.world.matches(world, x, y, z, compiled)
    override fun holds(compiled: Any): Boolean = world.holds(compiled)
    override fun set(world: String, x: Int, y: Int, z: Int, compiled: Any, fresh: Boolean) = this.world.set(world, x, y, z, compiled, fresh)
    override fun clearOut(world: String, box: Box, front: Location) = this.world.clearOut(world, box, front)
    override fun isFree(world: String, x: Int, y: Int, z: Int): Boolean = this.world.isFree(world, x, y, z)
    override fun isSign(world: String, x: Int, y: Int, z: Int): Boolean = this.world.isSign(world, x, y, z)
    override fun placeSign(world: String, x: Int, y: Int, z: Int, material: String, facing: Int) = this.world.placeSign(world, x, y, z, material, facing)
    override fun writeSign(world: String, x: Int, y: Int, z: Int, lines: List<Component>): Boolean = this.world.writeSign(world, x, y, z, lines)
    override fun removeSign(world: String, x: Int, y: Int, z: Int) = this.world.removeSign(world, x, y, z)
    override fun dust(player: PlayerRef, x: Double, y: Double, z: Double, rgb: Int, size: Float, count: Int, spread: Double) = world.dust(player, x, y, z, rgb, size, count, spread)
    override fun giveWand(player: PlayerRef, name: Component, lore: List<Component>) = world.giveWand(player, name, lore)

    /** Whether [item] is xPlots' wand: a blaze rod first, so a click with anything else costs one check. */
    fun isWand(item: ItemStack?): Boolean = item != null && item.type == Material.BLAZE_ROD && world.isWand(item)

    override fun icon(id: String): Icon? = world.icon(id)
    override fun head(player: PlayerRef, name: String): String? = world.head(player, name)
    override val menus: Menus get() = platform.menus
    override fun countItems(player: PlayerRef, currency: ItemCurrency): Int = world.countItems(player, currency)
    override fun takeItems(player: PlayerRef, currency: ItemCurrency, amount: Int): Boolean = world.takeItems(player, currency, amount)
    override fun giveItems(player: PlayerRef, currency: ItemCurrency, amount: Int) = world.giveItems(player, currency, amount)
    override fun regions(): RegionBackend? = world.regions()
    override fun worldEditSelection(player: PlayerRef): Pair<String, Box>? = world.worldEditSelection(player)

    /** What this server has beyond Spigot 1.13.2's API, found once at startup. */
    internal class Paths(
        val asyncChunks: Boolean,
        val tickets: Boolean,
        val asyncTeleport: Boolean,
        val adventure: Boolean,
        val signWax: Boolean,
        val persistentData: Boolean,
        val customModelData: Boolean,
        val cachedOffline: Boolean,
        val fallingOrigin: Boolean,
    ) {
        companion object {
            fun detect(): Paths {
                val loader = Player::class.java.classLoader
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
                val adventure = try {
                    Player::class.java.getMethod("sendActionBar", Class.forName("net.kyori.adventure.text.Component", false, loader))
                    true
                } catch (e: ReflectiveOperationException) {
                    false
                } catch (e: LinkageError) {
                    false
                }
                return Paths(
                    asyncChunks = has("org.bukkit.World", "getChunkAtAsync", int, int, boolean),
                    tickets = has("org.bukkit.Chunk", "addPluginChunkTicket", Plugin::class.java),
                    asyncTeleport = has("org.bukkit.entity.Entity", "teleportAsync", org.bukkit.Location::class.java, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause::class.java),
                    adventure = adventure,
                    signWax = has("org.bukkit.block.Sign", "setWaxed", boolean),
                    persistentData = has("org.bukkit.inventory.meta.ItemMeta", "getPersistentDataContainer"),
                    customModelData = has("org.bukkit.inventory.meta.ItemMeta", "setCustomModelData", Class.forName("java.lang.Integer")),
                    cachedOffline = has("org.bukkit.Server", "getOfflinePlayerIfCached", String::class.java),
                    fallingOrigin = has("org.bukkit.entity.Entity", "getOrigin"),
                )
            }
        }
    }
}
