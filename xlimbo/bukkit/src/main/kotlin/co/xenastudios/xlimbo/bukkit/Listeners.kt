package co.xenastudios.xlimbo.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xlimbo.Switch
import co.xenastudios.xlimbo.api.ReturnRoute
import co.xenastudios.xlimbo.config.XLimboConfig
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Hanging
import org.bukkit.entity.Interaction
import org.bukkit.entity.Marker
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.ExplosionPrimeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.PortalCreateEvent
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * xLimbo's listeners on Paper, one class per switch, registered only while its switch is on. Each
 * protection acts only in the limbo world. With `send-back.via: proxy` the whole server is the
 * limbo; with `teleport` it's one world of a server players play on, so what touches players
 * outside it (their join and quit messages, their spawn) is left alone.
 */
internal object Listeners {
    fun of(switch: Int, server: BukkitLimboServer): Listener = when (switch) {
        Switch.ARRIVALS -> Arrivals(server)
        Switch.HIDE_OTHERS -> HideOthers(server)
        Switch.FLOOR -> Floor(server)
        Switch.VOID -> Void(server)
        Switch.VOID_DAMAGE -> VoidDamage(server)
        Switch.PORTALS -> Portals(server)
        Switch.LIQUIDS -> Liquids(server)
        Switch.GRAVITY -> Gravity(server)
        Switch.FIRE -> Fire(server)
        Switch.EXPLOSIONS -> Explosions(server)
        Switch.MOBS -> Mobs(server)
        Switch.REDSTONE -> Redstone(server)
        Switch.ITEMS -> Items(server)
        Switch.ENTITY_ITEMS -> EntityItems(server)
        else -> BuildGuard(server)
    }

    /**
     * Removes stray entities: everything but players and what builds are made of (displays,
     * markers, interactions, frames and paintings, armour stands), and anything tagged `xlimbo.keep`.
     */
    fun sweep(world: LimboWorld) {
        val target = world.world ?: return
        for (entity in target.entities) if (stray(entity)) entity.remove()
    }

    private fun stray(entity: Entity): Boolean =
        !(entity is Player || entity is Display || entity is Marker || entity is Interaction || entity is Hanging || entity is ArmorStand) &&
            "xlimbo.keep" !in entity.scoreboardTags

    /** A protection: a listener that only cares about the limbo world. */
    abstract class Protection(val server: BukkitLimboServer) : Listener {
        fun here(world: World?): Boolean = server.world.isLimbo(world)

        fun here(block: Block): Boolean = here(block.world)

        fun here(entity: Entity): Boolean = here(entity.world)
    }

    /** Where players land and what they're given: on arrival, on respawn, and quietly. Always on. */
    class Arrivals(server: BukkitLimboServer) : Protection(server) {
        private val xlimbo get() = server.xlimbo
        private val proxy: Boolean get() = server.route == ReturnRoute.PROXY

        // Chosen before the player is in the world, so they're placed once, where the land is
        // already loaded: no teleport straight after joining. Only where the whole server is the limbo.
        @EventHandler(priority = EventPriority.HIGH)
        fun onSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
            if (!proxy) return
            server.world.spawn()?.let { event.spawnLocation = it }
        }

        @EventHandler(priority = EventPriority.HIGH)
        fun onJoin(event: PlayerJoinEvent) {
            val player = event.player
            val config = xlimbo.config
            if (proxy) {
                if (config[XLimboConfig.join.suppressJoinMessage]) event.joinMessage(null)
                val spawn = server.world.spawn()
                // Joined before the world existed, or while it was being rebuilt: bring them in.
                if (spawn != null && !here(player.world)) player.teleportAsync(spawn)
                xlimbo.arrived(player.ref, joined = true)
            } else if (here(player.world)) {
                // Left in limbo when they quit: still in it.
                if (config[XLimboConfig.join.suppressJoinMessage]) event.joinMessage(null)
                xlimbo.arrived(player.ref, joined = true)
            }
        }

        // Flight goes with a game mode change, which is applied after this event: give it back on
        // the next tick so nobody drops.
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onGameModeChange(event: PlayerGameModeChangeEvent) {
            val player = event.player
            if (xlimbo.config[XLimboConfig.player.enableFlight] && here(player.world)) {
                player.scheduler.run(server.xlimboPlugin, { if (here(player.world)) player.allowFlight = true }, null)
            }
        }

        @EventHandler(priority = EventPriority.HIGH)
        fun onRespawn(event: PlayerRespawnEvent) {
            if (!proxy && !here(event.player.world)) return
            server.world.spawn()?.let { event.respawnLocation = it }
        }

        // Where a player was before a teleport into limbo on this server, from any plugin's (xTeleport's `xlimbo` destination, /tp).
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onTeleport(event: PlayerTeleportEvent) {
            if (proxy || server.world.moving) return
            if (here(event.to.world) && !here(event.from.world)) server.remember(event.player, event.from)
        }

        // Into or out of limbo on this server: an arrival, or a leaving with what they had given back.
        @EventHandler(priority = EventPriority.MONITOR)
        fun onWorldChange(event: PlayerChangedWorldEvent) {
            if (proxy || server.world.moving) return
            val player = event.player
            val into = here(player.world)
            val from = here(event.from)
            if (into && !from) {
                xlimbo.arrived(player.ref, joined = false)
            } else if (from && !into) {
                xlimbo.left(player.ref, quit = false)
                server.unequip(player)
                server.forgetPlace(player.uniqueId)
            }
        }

        @EventHandler(priority = EventPriority.HIGH)
        fun onQuit(event: PlayerQuitEvent) {
            val player = event.player
            val inLimbo = here(player.world)
            xlimbo.left(player.ref, quit = true)
            if ((proxy || inLimbo) && xlimbo.config[XLimboConfig.quit.suppressQuitMessage]) event.quitMessage(null)
        }

        // Nobody needs to hear that someone got "Minecraft" for arriving.
        @EventHandler(priority = EventPriority.HIGH)
        fun onAdvancement(event: PlayerAdvancementDoneEvent) {
            if (here(event.player.world)) event.message(null)
        }

        @EventHandler(priority = EventPriority.HIGH)
        fun onDeath(event: PlayerDeathEvent) {
            if ((proxy || here(event.player.world)) && xlimbo.config[XLimboConfig.death.suppressDeathMessage]) event.deathMessage(null)
        }
    }

    /**
     * Players can't see each other. Hundreds of players in one spot each tracking every other is a
     * limbo's biggest load; hidden, nobody is tracked or sent anyone's movement. With
     * `send-back.via: proxy`, everyone on the server; with `teleport`, players in limbo from each
     * other. Hidden with xLimbo's own handle, so another plugin's hiding (xVanish's) is never undone.
     */
    class HideOthers(server: BukkitLimboServer) : Protection(server) {
        private val plugin get() = server.xlimboPlugin

        private fun pairs(player: Player): Boolean = server.route == ReturnRoute.PROXY || here(player.world)

        fun start() {
            val online = Bukkit.getOnlinePlayers()
            for (player in online) for (other in online) if (player !== other && pairs(player) && pairs(other)) player.hidePlayer(plugin, other)
        }

        fun stop() {
            val online = Bukkit.getOnlinePlayers()
            for (player in online) for (other in online) if (player !== other) player.showPlayer(plugin, other)
        }

        // Before the player is sent to anyone, so they're never tracked at all.
        @EventHandler(priority = EventPriority.LOWEST)
        fun onJoin(event: PlayerJoinEvent) {
            val joined = event.player
            if (!pairs(joined)) return
            for (other in Bukkit.getOnlinePlayers()) {
                if (other === joined || !pairs(other)) continue
                other.hidePlayer(plugin, joined)
                joined.hidePlayer(plugin, other)
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onWorldChange(event: PlayerChangedWorldEvent) {
            if (server.route == ReturnRoute.PROXY) return
            val player = event.player
            val inLimbo = here(player.world)
            for (other in Bukkit.getOnlinePlayers()) {
                if (other === player) continue
                if (inLimbo && here(other.world)) {
                    other.hidePlayer(plugin, player)
                    player.hidePlayer(plugin, other)
                } else {
                    other.showPlayer(plugin, player)
                    player.showPlayer(plugin, other)
                }
            }
        }
    }

    class Floor(server: BukkitLimboServer) : Protection(server) {
        private fun isFloor(block: Block): Boolean = block.y == server.world.floorY && here(block)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBreak(event: BlockBreakEvent) {
            if (isFloor(event.block)) event.isCancelled = true
        }

        // Nothing goes in or under the floor.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPlace(event: BlockPlaceEvent) {
            if (event.block.y <= server.world.floorY && here(event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPistonPush(event: BlockPistonExtendEvent) {
            cancelIfFloor(event, event.blocks)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPistonPull(event: BlockPistonRetractEvent) {
            cancelIfFloor(event, event.blocks)
        }

        private fun cancelIfFloor(event: Cancellable, moved: List<Block>) {
            if (moved.any(::isFloor)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEntityChange(event: EntityChangeBlockEvent) {
            if (isFloor(event.block)) event.isCancelled = true
        }

        // Even with explosions allowed, they can't hole the floor.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEntityExplode(event: EntityExplodeEvent) {
            event.blockList().removeIf(::isFloor)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBlockExplode(event: BlockExplodeEvent) {
            event.blockList().removeIf(::isFloor)
        }

        // An ice floor doesn't melt, a leaf floor doesn't decay.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onFade(event: BlockFadeEvent) {
            if (isFloor(event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDecay(event: LeavesDecayEvent) {
            if (isFloor(event.block)) event.isCancelled = true
        }
    }

    class Void(server: BukkitLimboServer) : Protection(server) {
        // On every move, so the cheapest check (height) goes first.
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        fun onMove(event: PlayerMoveEvent) {
            val to = event.to
            // Never above the floor, or players standing on it would be caught every step.
            val floorY = server.world.floorY
            val threshold = server.xlimbo.config[XLimboConfig.protections.voidProtection.thresholdY]
            if (to.y < minOf(threshold, floorY) && here(to.world)) {
                // Straight back up onto the floor: no teleport needed, just a new destination.
                event.to = Location(to.world, to.x, floorY + 1.0, to.z, to.yaw, to.pitch)
                event.player.fallDistance = 0f
            }
        }
    }

    /** No void or fall damage, so a rescue never becomes a death loop. */
    class VoidDamage(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDamage(event: EntityDamageEvent) {
            val cause = event.cause
            if ((cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.FALL) && event.entity is Player && here(event.entity)) {
                event.isCancelled = true
            }
        }
    }

    class Portals(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onCreate(event: PortalCreateEvent) {
            if (here(event.world)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPlayerPortal(event: PlayerPortalEvent) {
            if (here(event.from.world)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEntityPortal(event: EntityPortalEvent) {
            if (here(event.from.world)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW)
        fun onEye(event: PlayerInteractEvent) {
            val block = event.clickedBlock ?: return
            if (event.action == Action.RIGHT_CLICK_BLOCK && block.type == Material.END_PORTAL_FRAME && event.item?.type == Material.ENDER_EYE && here(block)) {
                event.isCancelled = true
            }
        }
    }

    class Liquids(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBucket(event: PlayerBucketEmptyEvent) {
            if (here(event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onFlow(event: BlockFromToEvent) {
            if (here(event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDispense(event: BlockDispenseEvent) {
            val item = event.item.type
            if ((item == Material.WATER_BUCKET || item == Material.LAVA_BUCKET || item == Material.POWDER_SNOW_BUCKET) && here(event.block)) event.isCancelled = true
        }
    }

    class Gravity(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPlace(event: BlockPlaceEvent) {
            if (event.block.type.hasGravity() && here(event.block)) event.isCancelled = true
        }

        // A block placed some other way (a command, a plugin) stays put rather than falling.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onFall(event: EntityChangeBlockEvent) {
            if (event.entity is FallingBlock && event.to.isAir && here(event.block)) event.isCancelled = true
        }
    }

    class Fire(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onIgnite(event: BlockIgniteEvent) {
            if (here(event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBurn(event: BlockBurnEvent) {
            if (here(event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onSpread(event: BlockSpreadEvent) {
            val source = event.source.type
            if ((source == Material.FIRE || source == Material.SOUL_FIRE) && here(event.block)) event.isCancelled = true
        }
    }

    class Explosions(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPrime(event: ExplosionPrimeEvent) {
            if (here(event.entity)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEntityExplode(event: EntityExplodeEvent) {
            if (here(event.entity)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBlockExplode(event: BlockExplodeEvent) {
            if (here(event.block)) event.isCancelled = true
        }
    }

    class Mobs(server: BukkitLimboServer) : Protection(server) {
        // Every way a mob appears but a plugin spawning one on purpose.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onSpawn(event: CreatureSpawnEvent) {
            if (event.spawnReason != CreatureSpawnEvent.SpawnReason.CUSTOM && here(event.entity)) event.isCancelled = true
        }
    }

    class Redstone(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW)
        fun onRedstone(event: BlockRedstoneEvent) {
            if (event.newCurrent != 0 && here(event.block)) event.newCurrent = 0
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPiston(event: BlockPistonExtendEvent) {
            if (here(event.block)) event.isCancelled = true
        }

        // A redstone block powers a dispenser without a redstone event.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDispense(event: BlockDispenseEvent) {
            if (here(event.block)) event.isCancelled = true
        }
    }

    class Items(server: BukkitLimboServer) : Protection(server) {
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDrop(event: PlayerDropItemEvent) {
            if (here(event.player)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onItem(event: ItemSpawnEvent) {
            if (here(event.entity)) event.isCancelled = true
        }
    }

    class EntityItems(server: BukkitLimboServer) : Protection(server) {
        // Armour stands, boats, minecarts and End crystals.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPlace(event: EntityPlaceEvent) {
            if (here(event.entity)) event.isCancelled = true
        }

        // Item frames and paintings.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onHang(event: HangingPlaceEvent) {
            if (here(event.entity)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW)
        fun onSpawnEgg(event: PlayerInteractEvent) {
            val item = event.item ?: return
            if (item.type.name.endsWith("_SPAWN_EGG") && here(event.player)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEggSpawn(event: CreatureSpawnEvent) {
            if (event.spawnReason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG && here(event.entity)) event.isCancelled = true
        }
    }

    /** Limits how fast each player places blocks, against lag machines. */
    class BuildGuard(server: BukkitLimboServer) : Protection(server) {
        // Server thread only: [the second, places in it] per player.
        private val places = HashMap<UUID, LongArray>()

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPlace(event: BlockPlaceEvent) {
            if (!here(event.block)) return
            val second = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime())
            val count = places.getOrPut(event.player.uniqueId) { LongArray(2) }
            if (count[0] != second) {
                count[0] = second
                count[1] = 0
            }
            if (++count[1] > server.xlimbo.config[XLimboConfig.protections.buildGuard.maxPlacesPerSecond]) event.isCancelled = true
        }

        @EventHandler
        fun onQuit(event: PlayerQuitEvent) {
            places.remove(event.player.uniqueId)
        }
    }
}
