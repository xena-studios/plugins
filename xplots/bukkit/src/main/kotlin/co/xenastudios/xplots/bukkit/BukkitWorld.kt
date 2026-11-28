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
 * The world, signs, items and region systems for [BukkitPlotServer], made the first time one is
 * needed, so none of it loads as the server starts. What xPlots does on a Bukkit-family server from 1.13 (block data's first version) to the newest
 * Paper and Folia. Compiled against Spigot 1.13.2's API; what Paper and newer servers add is in
 * `modern`, used only where [Paths] found it at startup: chunks loaded off the server thread,
 * teleports without blocking, signs' wax, an item's persistent data and custom model data, the
 * cached offline player. WorldGuard and WorldEdit are reached through classes of their own, loaded
 * only when they're installed.
 */
internal class BukkitWorld(private val plugin: Plugin, private val platform: BukkitPlatform, private val cache: PlayerCache, val paths: BukkitPlotServer.Paths) : PlotServer {
    private val bukkit = plugin.server
    private val wandKey: NamespacedKey = NamespacedKey(plugin, "wand")

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    private fun world(name: String): World? = bukkit.getWorld(name)

    private fun block(world: String, x: Int, y: Int, z: Int): Block? = world(world)?.getBlockAt(x, y, z)

    // ---- players ----

    override fun online(): List<PlayerRef> = bukkit.onlinePlayers.map { it.ref }
    override fun isOnline(player: PlayerRef): Boolean = player(player) != null
    override fun name(player: PlayerRef): String? = player(player)?.name ?: cache.get(player)?.name

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        val p = player(player) ?: return
        val legacy = platform.text.legacy.draw(message)
        if (paths.adventure) co.xenastudios.xplots.bukkit.modern.AdventureBar.send(p, legacy) else SpigotBar.send(p, legacy)
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true

    override fun highestNumbered(player: PlayerRef, prefix: String): Int {
        val p = player(player) ?: return -1
        var best = -1
        for (permission in p.effectivePermissions) {
            if (!permission.value || !permission.permission.startsWith(prefix)) continue
            permission.permission.substring(prefix.length).toIntOrNull()?.let { best = maxOf(best, it) }
        }
        return best
    }

    override fun position(player: PlayerRef): Location? {
        val at = player(player)?.location ?: return null
        if (at.world == null) return null
        return at.toCore()
    }

    override fun known(name: String): Known? {
        bukkit.getPlayerExact(name)?.let { return Known(it.ref, it.name) }
        cache.find(name)?.let { return Known(it.player, it.name) }
        if (paths.cachedOffline) {
            val offline = co.xenastudios.xplots.bukkit.modern.Offline.cached(bukkit, name) ?: return null
            return Known(offline.ref, offline.name ?: name)
        }
        return null
    }

    override fun teleport(player: PlayerRef, to: Location) {
        val p = player(player) ?: return
        val target = to.toBukkit(bukkit) ?: return
        if (paths.asyncTeleport) co.xenastudios.xplots.bukkit.modern.Teleports.teleport(p, target) else p.teleport(target)
    }

    override fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean {
        val v = player(viewer) ?: return true
        val t = player(target) ?: return false
        return v.canSee(t)
    }

    // ---- the world ----

    override fun hasWorld(world: String): Boolean = world(world) != null

    override fun worlds(): List<String> = bukkit.worlds.map { it.name }

    override fun isLoaded(world: String, chunkX: Int, chunkZ: Int): Boolean = world(world)?.isChunkLoaded(chunkX, chunkZ) == true

    override fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean> {
        val w = world(world) ?: return pendingOf(false)
        if (paths.asyncChunks) return co.xenastudios.xplots.bukkit.modern.Chunks.load(plugin, w, chunkX, chunkZ, paths.tickets)
        // Spigot: on the server thread.
        val out = newPending<Boolean>()
        platform.scheduler.run(Where.Global) {
            w.loadChunk(chunkX, chunkZ, true)
            out.resolve(true)
        }
        return out
    }

    override fun release(world: String, chunkX: Int, chunkZ: Int) {
        val w = world(world) ?: return
        if (paths.tickets) co.xenastudios.xplots.bukkit.modern.Chunks.release(plugin, w, chunkX, chunkZ)
    }

    override fun stateAt(world: String, x: Int, y: Int, z: Int): String = block(world, x, y, z)?.blockData?.asString ?: "minecraft:air"

    override fun compile(state: String): Any? = try {
        Bukkit.createBlockData(state)
    } catch (e: IllegalArgumentException) {
        // From a newer or older Minecraft (a renamed block or property): its block with default
        // properties where it still exists, left as it is where it doesn't.
        val material = Material.matchMaterial(state.substringBefore('['))
        if (material != null && material.isBlock) material.createBlockData() else null
    }

    override fun matches(world: String, x: Int, y: Int, z: Int, compiled: Any): Boolean = block(world, x, y, z)?.blockData == compiled

    override fun holds(compiled: Any): Boolean {
        val name = (compiled as? BlockData)?.material?.name ?: return false
        return HOLDERS.any { it in name }
    }

    override fun set(world: String, x: Int, y: Int, z: Int, compiled: Any, fresh: Boolean) {
        val block = block(world, x, y, z) ?: return
        val data = compiled as? BlockData ?: return
        // A container set without physics would spill what it held: empty it first.
        (block.state as? Container)?.inventory?.clear()
        if (fresh) block.setType(Material.AIR, false)
        block.setBlockData(data, false)
    }

    override fun clearOut(world: String, box: Box, front: Location) {
        val w = world(world) ?: return
        val centre = org.bukkit.Location(w, (box.minX + box.maxX + 1) / 2.0, (box.minY + box.maxY + 1) / 2.0, (box.minZ + box.maxZ + 1) / 2.0)
        val target = front.toBukkit(bukkit)
        for (entity in w.getNearbyEntities(centre, box.width / 2.0 + 1, box.height / 2.0 + 1, box.depth / 2.0 + 1)) {
            val at = entity.location
            if (!box.contains(at.blockX, at.blockY, at.blockZ)) continue
            if (entity is Player) {
                if (target != null) {
                    if (paths.asyncTeleport) co.xenastudios.xplots.bukkit.modern.Teleports.teleport(entity, target) else entity.teleport(target)
                }
            } else if (entity is Item || entity is Hanging || entity is ArmorStand || entity is FallingBlock || entity is ExperienceOrb || entity is TNTPrimed ||
                ((entity is Minecart || entity is Boat) && entity.passengers.isEmpty())
            ) {
                // Not mobs: a pet standing in the plot is someone's.
                entity.remove()
            }
        }
    }

    override fun isFree(world: String, x: Int, y: Int, z: Int): Boolean {
        val block = block(world, x, y, z) ?: return false
        if (block.isEmpty) return true
        return block.type.name in REPLACEABLE
    }

    // ---- signs ----

    override fun isSign(world: String, x: Int, y: Int, z: Int): Boolean = block(world, x, y, z)?.type?.let(::isSign) == true

    override fun placeSign(world: String, x: Int, y: Int, z: Int, material: String, facing: Int) {
        val block = block(world, x, y, z) ?: return
        val type = Material.matchMaterial(material)?.takeIf { isStandingSign(it) } ?: Material.matchMaterial("oak_sign") ?: Material.matchMaterial("sign") ?: return
        val data = type.createBlockData()
        if (data is Rotatable) data.rotation = BlockFace.valueOf(Facing.name(facing))
        block.setBlockData(data, false)
    }

    override fun writeSign(world: String, x: Int, y: Int, z: Int, lines: List<Component>): Boolean {
        val block = block(world, x, y, z) ?: return false
        val sign = block.state as? Sign ?: return false
        for (i in 0 until 4) {
            @Suppress("DEPRECATION")
            sign.setLine(i, if (i < lines.size) platform.text.legacy.draw(lines[i]) else "")
        }
        // Players can't edit it (1.20 on; before then a sign can't be edited after it's placed).
        if (paths.signWax) co.xenastudios.xplots.bukkit.modern.Signs.wax(sign)
        sign.update(true, false)
        return true
    }

    override fun removeSign(world: String, x: Int, y: Int, z: Int) {
        val block = block(world, x, y, z) ?: return
        if (isSign(block.type)) block.setType(Material.AIR, false)
    }

    // ---- what players see and hold ----

    private val dust: Particle? by lazy {
        try {
            Particle.valueOf("DUST")
        } catch (e: IllegalArgumentException) {
            try {
                Particle.valueOf("REDSTONE")
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    override fun dust(player: PlayerRef, x: Double, y: Double, z: Double, rgb: Int, size: Float, count: Int, spread: Double) {
        val p = player(player) ?: return
        val particle = dust ?: return
        p.spawnParticle(particle, x, y, z, count, spread, spread * 2, spread, 0.0, Particle.DustOptions(Color.fromRGB(rgb and 0xFFFFFF), size))
    }

    override fun giveWand(player: PlayerRef, name: Component, lore: List<Component>) {
        val p = player(player) ?: return
        val wand = ItemStack(Material.BLAZE_ROD)
        val meta = wand.itemMeta ?: return
        meta.setDisplayName(RESET + platform.text.legacy.draw(name))
        meta.lore = lore.map { RESET + platform.text.legacy.draw(it) }
        Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"))?.let { meta.addEnchant(it, 1, true) }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        if (paths.persistentData) co.xenastudios.xplots.bukkit.modern.Marks.mark(meta, wandKey)
        wand.itemMeta = meta
        wandName = meta.displayName
        p.inventory.addItem(wand).values.forEach { p.world.dropItem(p.location, it) }
    }

    @Volatile
    private var wandName: String? = null

    /** Whether [item] is xPlots' wand: marked as one (1.14 on), or a blaze rod named as the last one given (1.13). */
    fun isWand(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.BLAZE_ROD || !item.hasItemMeta()) return false
        val meta = item.itemMeta ?: return false
        if (paths.persistentData) return co.xenastudios.xplots.bukkit.modern.Marks.marked(meta, wandKey)
        return meta.hasDisplayName() && meta.displayName == wandName
    }

    override fun icon(id: String): Icon? {
        if (id == "air" || id == "minecraft:air") return null
        val material = Material.matchMaterial(id) ?: FALLBACKS[id.removePrefix("minecraft:")]?.let(Material::matchMaterial) ?: Material.STONE
        return Icon(material.key.toString())
    }

    override fun head(player: PlayerRef, name: String): String? = try {
        val stack = ItemStack(Material.PLAYER_HEAD)
        val meta = stack.itemMeta as? SkullMeta
        if (meta == null) {
            null
        } else {
            meta.owningPlayer = bukkit.getOfflinePlayer(java.util.UUID.fromString(player.toString()))
            stack.itemMeta = meta
            menus.data(stack)
        }
    } catch (e: Exception) {
        null
    }

    override val menus: Menus get() = platform.menus

    // ---- item currency ----

    private fun matches(stack: ItemStack?, currency: ItemCurrency): Boolean {
        if (stack == null) return false
        val material = Material.matchMaterial(currency.material) ?: return false
        if (stack.type != material) return false
        if (currency.name.isBlank() && currency.customModelData == 0) return true
        val meta = stack.itemMeta ?: return false
        if (currency.name.isNotBlank()) {
            if (!meta.hasDisplayName()) return false
            val shown = org.bukkit.ChatColor.stripColor(meta.displayName) ?: ""
            if (!shown.trim().equals(currency.name.trim(), ignoreCase = true)) return false
        }
        return currency.customModelData == 0 || paths.customModelData && co.xenastudios.xplots.bukkit.modern.Marks.modelData(meta) == currency.customModelData
    }

    override fun countItems(player: PlayerRef, currency: ItemCurrency): Int =
        player(player)?.inventory?.storageContents?.filter { matches(it, currency) }?.sumOf { it.amount } ?: 0

    override fun takeItems(player: PlayerRef, currency: ItemCurrency, amount: Int): Boolean {
        val p = player(player) ?: return false
        if (countItems(player, currency) < amount) return false
        var needed = amount
        val contents = p.inventory.storageContents
        for (i in contents.indices) {
            if (needed <= 0) break
            val stack = contents[i]
            if (!matches(stack, currency)) continue
            val used = minOf(needed, stack.amount)
            needed -= used
            stack.amount = stack.amount - used
            contents[i] = if (stack.amount <= 0) null else stack
        }
        p.inventory.storageContents = contents
        return true
    }

    override fun giveItems(player: PlayerRef, currency: ItemCurrency, amount: Int) {
        val p = player(player) ?: return
        val material = Material.matchMaterial(currency.material) ?: return
        val stack = ItemStack(material)
        val meta = stack.itemMeta
        if (meta != null) {
            if (currency.name.isNotBlank()) meta.setDisplayName(RESET + currency.name)
            if (currency.customModelData != 0 && paths.customModelData) co.xenastudios.xplots.bukkit.modern.Marks.setModelData(meta, currency.customModelData)
            stack.itemMeta = meta
        }
        var left = amount
        while (left > 0) {
            val give = stack.clone()
            give.amount = minOf(stack.maxStackSize, left)
            left -= give.amount
            // What doesn't fit is dropped at their feet, never lost.
            p.inventory.addItem(give).values.forEach { p.world.dropItemNaturally(p.location, it) }
        }
    }

    // ---- WorldGuard and WorldEdit ----

    @Volatile
    private var madeRegions: RegionBackend? = null

    @Volatile
    private var regionsChecked = false

    override fun regions(): RegionBackend? {
        if (regionsChecked) return madeRegions
        regionsChecked = true
        if (!bukkit.pluginManager.isPluginEnabled("WorldGuard")) return null
        madeRegions = try {
            WorldGuardHook.regions(plugin)
        } catch (e: Exception) {
            platform.log.warn("This WorldGuard isn't one xPlots can use ($e), so fields get no region.")
            null
        } catch (e: LinkageError) {
            platform.log.warn("This WorldGuard isn't one xPlots can use ($e), so fields get no region.")
            null
        }
        return madeRegions
    }

    override fun worldEditSelection(player: PlayerRef): Pair<String, Box>? {
        val p = player(player) ?: return null
        if (!bukkit.pluginManager.isPluginEnabled("WorldEdit") && !bukkit.pluginManager.isPluginEnabled("FastAsyncWorldEdit")) return null
        return try {
            WorldEditHook.selection(plugin, p)
        } catch (e: LinkageError) {
            null
        }
    }

    private object SpigotBar {
        fun send(player: Player, legacy: String) {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, *net.md_5.bungee.api.chat.TextComponent.fromLegacyText(legacy))
        }
    }

    companion object {
        private const val RESET = "§r"

        /** Blocks that hold something, by a part of their name: containers, signs, pots. */
        private val HOLDERS = listOf(
            "CHEST", "BARREL", "FURNACE", "SMOKER", "HOPPER", "DROPPER", "DISPENSER", "SHULKER_BOX", "BREWING_STAND", "LECTERN",
            "JUKEBOX", "SIGN", "CHISELED_BOOKSHELF", "DECORATED_POT", "CRAFTER", "BEEHIVE", "BEE_NEST", "CAMPFIRE", "SPAWNER",
        )

        /** Blocks a sign may replace when it's moved there. */
        private val REPLACEABLE = setOf(
            "AIR", "CAVE_AIR", "VOID_AIR", "GRASS", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "SNOW", "DEAD_BUSH", "VINE", "WATER",
        )

        /** Items 1.13 had under other names, for the menus' defaults. */
        private val FALLBACKS = mapOf("oak_sign" to "sign", "dark_oak_sign" to "sign", "red_dye" to "rose_red", "green_dye" to "cactus_green", "yellow_dye" to "dandelion_yellow")

        fun isSign(type: Material): Boolean = type.name == "SIGN" || type.name.endsWith("_SIGN")

        fun isStandingSign(type: Material): Boolean = type.name == "SIGN" || (type.name.endsWith("_SIGN") && "WALL" !in type.name && "HANGING" !in type.name)
    }
}
