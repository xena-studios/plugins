package co.xenastudios.xutilities.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.BukkitServer
import co.xenastudios.core.bukkit.loaded
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.toBukkit
import co.xenastudios.core.bukkit.toCore
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.LegacyRenderer
import co.xenastudios.xutilities.Column
import co.xenastudios.xutilities.Features
import co.xenastudios.xutilities.GameMode
import co.xenastudios.xutilities.MoveListener
import co.xenastudios.xutilities.Server
import co.xenastudios.xutilities.Station
import co.xenastudios.xutilities.bukkit.modern.clearOffHand
import co.xenastudios.xutilities.bukkit.modern.mainHand
import co.xenastudios.xutilities.bukkit.modern.openMenu
import co.xenastudios.xutilities.bukkit.modern.openPaperMenu
import co.xenastudios.xutilities.bukkit.modern.ownsRegion
import co.xenastudios.xutilities.bukkit.modern.passable
import co.xenastudios.xutilities.bukkit.modern.ping
import co.xenastudios.xutilities.bukkit.modern.repairMeta
import co.xenastudios.xutilities.bukkit.modern.setMainHand
import co.xenastudios.xutilities.bukkit.modern.setRotation
import co.xenastudios.xutilities.bukkit.modern.teleportAsync
import co.xenastudios.core.common.config.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.GameMode as BukkitGameMode

/**
 * What xUtilities does to players on a Bukkit-family server, from 1.8.8 to the newest Paper and
 * Folia. Compiled against Spigot 1.8.8's API; what newer servers add is in `modern`, used only
 * where [Paths] found it at startup.
 */
internal class BukkitXServer(private val plugin: Plugin, private val platform: BukkitPlatform) : Server {
    private val bukkit = plugin.server
    private val paths = Paths.detect(platform.server)

    override val features: Features = Features(
        stations = Station.entries.filter { !paths.opens(it) }.associateWith {
            "Paper 1.14 or newer, or Spigot 1.21.4 or newer"
        },
        ping = if (paths.ping || paths.spigotPing != null) null else "Minecraft 1.17 or newer, or Paper",
        teleportEvents = true,
    )

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun location(player: PlayerRef): Location? = player(player)?.location?.toCore()

    override fun isLoaded(world: WorldRef): Boolean = world.loaded(bukkit) != null

    override fun setGameMode(player: PlayerRef, mode: GameMode) {
        player(player)?.gameMode = when (mode) {
            GameMode.SURVIVAL -> BukkitGameMode.SURVIVAL
            GameMode.CREATIVE -> BukkitGameMode.CREATIVE
            GameMode.ADVENTURE -> BukkitGameMode.ADVENTURE
            GameMode.SPECTATOR -> BukkitGameMode.SPECTATOR
        }
    }

    override fun toggleFlight(player: PlayerRef): Boolean {
        val p = player(player) ?: return false
        val on = !p.allowFlight
        p.allowFlight = on
        if (!on) p.isFlying = false
        return on
    }

    override fun isFlying(player: PlayerRef): Boolean = player(player)?.isFlying == true

    override fun setSpeed(player: PlayerRef, flying: Boolean, speed: Float) {
        val p = player(player) ?: return
        if (flying) p.flySpeed = speed else p.walkSpeed = speed
    }

    override fun setRotation(player: PlayerRef, yaw: Float, pitch: Float) {
        val p = player(player) ?: return
        if (paths.rotation) {
            setRotation(p, yaw, pitch)
        } else {
            // Before 1.13: a teleport to where they stand, facing the new way.
            val here = p.location
            here.yaw = yaw
            here.pitch = pitch
            p.teleport(here)
        }
    }

    override fun ping(player: PlayerRef): Int {
        val p = player(player) ?: return 0
        if (paths.ping) return ping(p)
        val method = paths.spigotPing ?: return 0
        return (method.invoke(p.spigot()) as? Int) ?: 0
    }

    @Suppress("DEPRECATION")
    override fun heal(player: PlayerRef, hunger: Boolean, fire: Boolean, harmfulEffects: Boolean): Boolean {
        val p = player(player) ?: return false
        if (p.isDead) return false
        p.health = p.maxHealth
        if (hunger) feed(p)
        if (fire) p.fireTicks = 0
        if (harmfulEffects) {
            for (effect: PotionEffect in p.activePotionEffects.toList()) {
                if (effect.type.name.lowercase() in HARMFUL) p.removePotionEffect(effect.type)
            }
        }
        return true
    }

    override fun feed(player: PlayerRef) {
        player(player)?.let(::feed)
    }

    private fun feed(p: Player) {
        p.foodLevel = 20
        p.saturation = 20f
        p.exhaustion = 0f
    }

    @Suppress("DEPRECATION")
    override fun repair(player: PlayerRef, all: Boolean): Int {
        val inventory = player(player)?.inventory ?: return 0
        if (!all) {
            val held = (if (paths.mainHand) mainHand(inventory) else inventory.itemInHand) ?: return 0
            if (!repair(held)) return 0
            if (paths.mainHand) setMainHand(inventory, held) else inventory.setItemInHand(held)
            return 1
        }
        var repaired = 0
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (repair(item)) {
                inventory.setItem(slot, item)
                repaired++
            }
        }
        // Before 1.9 the armour isn't part of the inventory's slots.
        if (!paths.mainHand) {
            val armor = inventory.armorContents
            var changed = false
            for (item in armor) if (item != null && repair(item)) {
                repaired++
                changed = true
            }
            if (changed) inventory.armorContents = armor
        }
        return repaired
    }

    @Suppress("DEPRECATION")
    private fun repair(item: ItemStack): Boolean {
        if (item.type.maxDurability <= 0) return false
        if (paths.damageMeta) return repairMeta(item)
        if (item.durability.toInt() == 0) return false
        item.durability = 0
        return true
    }

    override fun clear(player: PlayerRef, armor: Boolean) {
        val inventory: PlayerInventory = player(player)?.inventory ?: return
        if (armor) {
            inventory.clear()
            inventory.armorContents = arrayOfNulls(4)
            if (paths.mainHand) clearOffHand(inventory)
        } else {
            // The hotbar and the 27 slots above it.
            for (slot in 0 until 36) inventory.setItem(slot, null)
        }
    }

    override fun openEnderChest(viewer: PlayerRef, owner: PlayerRef): Boolean {
        val v = player(viewer) ?: return false
        val o = player(owner) ?: return false
        // On Folia an inventory may only be touched from its owner's region: another player's
        // chest opens only when they're in this one.
        if (v !== o && paths.folia && !ownsRegion(o)) return false
        v.openInventory(o.enderChest)
        return true
    }

    override fun openStation(player: PlayerRef, station: Station) {
        val p = player(player) ?: return
        when {
            paths.menuType -> openMenu(p, station.command)
            paths.paperMenus -> openPaperMenu(p, station.command)
            else -> p.openWorkbench(null, true)
        }
    }

    override fun openTrash(player: PlayerRef, title: Component) {
        val p = player(player) ?: return
        // Nothing holds this inventory, so whatever's in it is gone once it closes. A legacy
        // title: every version takes one, and before 1.13 it's 32 characters at most.
        val text = LEGACY.draw(title).let { if (platform.server.atLeast(1, 13)) it else it.take(32) }
        p.openInventory(bukkit.createInventory(null, 36, text))
    }

    override fun playSound(player: PlayerRef, sound: Sound) {
        if (sound.isNone) return
        val p = player(player) ?: return
        p.playSound(p.location, sound.name, sound.volume, sound.pitch)
    }

    // Bukkit's sound names changed in 1.9 and its registry in 1.21: there's no one way to ask.
    override fun knowsSound(sound: Sound): Boolean? = null

    override fun column(player: PlayerRef): Column? {
        val p = player(player) ?: return null
        val world = p.world
        val x = p.location.blockX
        val z = p.location.blockZ
        val ceiling = world.environment == World.Environment.NETHER
        val top = if (ceiling) world.maxHeight else minOf(world.maxHeight - 1, world.getHighestBlockYAt(x, z) + 1)
        return object : Column {
            override val top: Int = top
            override val hasCeiling: Boolean = ceiling
            override fun solid(y: Int): Boolean = world.getBlockAt(x, y, z).type.isSolid
            override fun passable(y: Int): Boolean {
                val block = world.getBlockAt(x, y, z)
                return if (paths.passable) passable(block) else !block.type.isSolid
            }
            override fun type(y: Int): String = world.getBlockAt(x, y, z).type.name
        }
    }

    override fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit) {
        val p = player(player) ?: return done(false)
        val target = to.toBukkit(bukkit) ?: return done(false)
        if (paths.folia) {
            teleportAsync(p, target) { moved -> platform.scheduler.run(co.xenastudios.core.common.scheduler.Where.on(player)) { done(moved) } }
        } else {
            done(p.teleport(target, TeleportCause.COMMAND))
        }
    }

    override fun watch(listener: MoveListener): AutoCloseable {
        val bukkitListener = object : Listener {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            fun onTeleport(event: PlayerTeleportEvent) {
                // Commands and plugins, not pearls or portals.
                if (event.cause in CAUSES) listener.teleported(event.player.ref, event.from.toCore())
            }

            @EventHandler(priority = EventPriority.MONITOR)
            fun onDeath(event: PlayerDeathEvent) {
                listener.died(event.entity.ref, event.entity.location.toCore())
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

    /** Which way to do each thing here, found once from the server's classes. */
    internal class Paths(
        val folia: Boolean,
        val menuType: Boolean,
        val paperMenus: Boolean,
        val ping: Boolean,
        val spigotPing: java.lang.reflect.Method?,
        val rotation: Boolean,
        val passable: Boolean,
        val mainHand: Boolean,
        val damageMeta: Boolean,
    ) {
        fun opens(station: Station): Boolean = menuType || paperMenus || station == Station.CRAFT

        companion object {
            fun detect(server: BukkitServer): Paths {
                fun has(type: String, method: String, vararg parameters: Class<*>): Boolean = try {
                    Class.forName(type, false, Player::class.java.classLoader).getMethod(method, *parameters)
                    true
                } catch (e: ReflectiveOperationException) {
                    false
                } catch (e: LinkageError) {
                    false
                }
                val location = org.bukkit.Location::class.java
                val builder = "org.bukkit.inventory.view.builder.LocationInventoryViewBuilder"
                return Paths(
                    folia = server.flavor == BukkitServer.Flavor.FOLIA,
                    menuType = has("org.bukkit.inventory.MenuType", "builder") && has(builder, "checkReachable", Boolean::class.javaPrimitiveType!!),
                    paperMenus = has("org.bukkit.entity.HumanEntity", "openAnvil", location, Boolean::class.javaPrimitiveType!!),
                    ping = has("org.bukkit.entity.Player", "getPing"),
                    spigotPing = try {
                        Class.forName("org.bukkit.entity.Player\$Spigot", false, Player::class.java.classLoader).getMethod("getPing")
                    } catch (e: ReflectiveOperationException) {
                        null
                    } catch (e: LinkageError) {
                        null
                    },
                    rotation = has("org.bukkit.entity.Entity", "setRotation", Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!),
                    passable = has("org.bukkit.block.Block", "isPassable"),
                    mainHand = has("org.bukkit.inventory.PlayerInventory", "getItemInMainHand"),
                    damageMeta = server.atLeast(1, 13) && has("org.bukkit.inventory.meta.Damageable", "hasDamage"),
                )
            }
        }
    }

    private companion object {
        val CAUSES = setOf(TeleportCause.COMMAND, TeleportCause.PLUGIN, TeleportCause.UNKNOWN)
        val LEGACY = LegacyRenderer()

        /** Harmful effects by every name Bukkit has given them. */
        val HARMFUL = setOf(
            "slow", "slowness", "slow_digging", "mining_fatigue", "harm", "instant_damage", "confusion", "nausea",
            "blindness", "hunger", "weakness", "poison", "wither", "levitation", "unluck", "bad_luck", "bad_omen",
            "darkness", "infested", "oozing", "weaving", "wind_charged", "raid_omen", "trial_omen",
        )
    }
}
