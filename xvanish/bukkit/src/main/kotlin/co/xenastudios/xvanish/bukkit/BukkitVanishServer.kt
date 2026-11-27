package co.xenastudios.xvanish.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.BukkitServer
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.text.Component
import co.xenastudios.xvanish.Effects
import co.xenastudios.xvanish.Switch
import co.xenastudios.xvanish.VanishServer
import co.xenastudios.xvanish.VanishView
import co.xenastudios.xvanish.api.XVanish
import co.xenastudios.xvanish.bukkit.modern.Advancements
import co.xenastudios.xvanish.bukkit.modern.Experience
import co.xenastudios.xvanish.bukkit.modern.Pickup
import co.xenastudios.xvanish.bukkit.modern.Projectiles
import co.xenastudios.xvanish.bukkit.modern.Raids
import co.xenastudios.xvanish.bukkit.modern.Sculk
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.concurrent.ConcurrentHashMap

/**
 * xVanish's [VanishServer] on Bukkit, from Spigot 1.8.8 to the newest Paper and Folia: Bukkit's
 * `hidePlayer`, so `canSee` is false for every other plugin, and on Folia the player hidden by
 * default first (`setVisibleByDefault`) so no viewer on another region's thread sees them for a
 * tick. What each server has is found once ([Paths]); the newer calls are in `modern/`.
 */
internal class BukkitVanishServer(private val plugin: JavaPlugin, private val platform: BukkitPlatform) : VanishServer {
    private val server = plugin.server
    private val paths = Paths(platform.server, platform.threading == co.xenastudios.core.common.platform.Threading.REGIONS)

    /** What's found once: which calls this server has. */
    class Paths(version: BukkitServer, folia: Boolean) {
        val paper = version.flavor == BukkitServer.Flavor.PAPER || version.flavor == BukkitServer.Flavor.FOLIA
        val pluginHiding = version.atLeast(1, 13)
        val byDefault = folia && version.atLeast(1, 19, 3)
        val nineOn = version.atLeast(1, 9)
        val entityPickup = version.atLeast(1, 12)
        val sculk = version.atLeast(1, 17)
        val raids = version.atLeast(1, 14)
        val locator = version.atLeast(1, 21, 6)
        val advancements = paper && version.atLeast(1, 16, 5)
        val experience = paper && version.atLeast(1, 12, 2)
        val projectileHit = version.atLeast(1, 11)
    }

    override val features: VanishServer.Features = VanishServer.Features(
        when {
            paths.byDefault -> "Folia's hidden-by-default players, then Bukkit's showPlayer"
            paths.pluginHiding -> "Bukkit's hidePlayer"
            else -> "Bukkit's hidePlayer (before 1.13)"
        },
        buildSet {
            addAll(listOf(Switch.PLAYERS, Switch.SERVER_LIST, Switch.DEATH_MESSAGES, Switch.MOB_TARGETING, Switch.PICKUP, Switch.PHYSICAL,
                Switch.PROJECTILES, Switch.SILENT_CONTAINERS, Switch.INVULNERABLE, Switch.CHAT))
            if (paths.advancements) add(Switch.ADVANCEMENT_MESSAGES)
            if (paths.sculk) add(Switch.SCULK)
            if (paths.raids) add(Switch.RAIDS)
        },
    )

    private fun player(ref: PlayerRef): Player? = ref.online(server)

    override fun online(): Collection<PlayerRef> = server.onlinePlayers.map { it.ref }

    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun console(message: Component) {
        platform.text.send(server.consoleSender, message)
    }

    override fun hide(viewer: PlayerRef, target: PlayerRef) {
        val v = player(viewer) ?: return
        val t = player(target) ?: return
        @Suppress("DEPRECATION")
        if (paths.pluginHiding) co.xenastudios.xvanish.bukkit.modern.hide(v, plugin, t) else v.hidePlayer(t)
    }

    override fun show(viewer: PlayerRef, target: PlayerRef) {
        val v = player(viewer) ?: return
        val t = player(target) ?: return
        @Suppress("DEPRECATION")
        if (paths.pluginHiding) co.xenastudios.xvanish.bukkit.modern.show(v, plugin, t) else v.showPlayer(t)
    }

    override fun hiddenByDefault(target: PlayerRef, hidden: Boolean): Boolean {
        if (!paths.byDefault) return false
        player(target)?.let { co.xenastudios.xvanish.bukkit.modern.visibleByDefault(it, !hidden) }
        return true
    }

    // What effects changed, per player, to undo exactly that.
    private class Applied(val flew: Boolean, val nightVision: Boolean, val bar: Any?, val locator: Double?)

    private val applied = ConcurrentHashMap<PlayerRef, Applied>()

    override fun effects(player: PlayerRef, effects: Effects?) {
        val ref = player
        val p = player(ref)
        val was = applied.remove(ref)
        if (p == null) return
        was?.let { undo(p, it) }
        if (effects == null) return
        p.setMetadata(XVanish.METADATA_KEY, FixedMetadataValue(plugin, true))
        val couldFly = p.allowFlight
        if (effects.fly) p.allowFlight = true
        val vision = effects.nightVision && !p.hasPotionEffect(PotionEffectType.NIGHT_VISION)
        if (vision) p.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, Int.MAX_VALUE, 0, true, false))
        if (effects.ignoreSleep) p.isSleepingIgnored = true
        if (paths.nineOn && effects.noCollisions) co.xenastudios.xvanish.bukkit.modern.collidable(p, false)
        if (paths.paper && effects.noSpawning) co.xenastudios.xvanish.bukkit.modern.affectsSpawning(p, false)
        val locator = if (paths.locator && effects.offLocatorBar) co.xenastudios.xvanish.bukkit.modern.locatorRange(p, 0.0) else null
        val bar = effects.bossBar?.takeIf { paths.nineOn }?.let { co.xenastudios.xvanish.bukkit.modern.bossBar(server, p, platform.text.legacy.draw(it)) }
        applied[ref] = Applied(couldFly, vision, bar, locator)
    }

    private fun undo(p: Player, was: Applied) {
        p.removeMetadata(XVanish.METADATA_KEY, plugin)
        if (!was.flew && p.gameMode != GameMode.CREATIVE && p.gameMode.name != "SPECTATOR") {
            p.isFlying = false
            p.allowFlight = false
        }
        if (was.nightVision) p.removePotionEffect(PotionEffectType.NIGHT_VISION)
        p.isSleepingIgnored = false
        if (paths.nineOn) co.xenastudios.xvanish.bukkit.modern.collidable(p, true)
        if (paths.paper) co.xenastudios.xvanish.bukkit.modern.affectsSpawning(p, true)
        was.locator?.let { co.xenastudios.xvanish.bukkit.modern.locatorRange(p, it) }
        was.bar?.let { co.xenastudios.xvanish.bukkit.modern.removeBossBar(it) }
    }

    override fun listen(switch: Int, view: VanishView): AutoCloseable {
        val listeners: List<Listener> = when (switch) {
            Switch.PLAYERS -> listOf(Players(view))
            Switch.SERVER_LIST -> listOf(ServerList(view))
            Switch.DEATH_MESSAGES -> listOf(Deaths(view))
            Switch.ADVANCEMENT_MESSAGES -> listOf(Advancements(view))
            Switch.MOB_TARGETING -> listOf(Targeting(view, pickup = false))
            Switch.PICKUP -> buildList {
                add(if (paths.entityPickup) Pickup(view) else OldPickup(view))
                // Experience orbs fly to a player through Bukkit's targeting.
                add(Targeting(view, pickup = true))
                if (paths.experience) add(Experience(view))
            }
            Switch.PHYSICAL -> listOf(Physical(view))
            Switch.SCULK -> listOf(Sculk(view))
            Switch.PROJECTILES -> if (paths.projectileHit) listOf(Projectiles(view), Harmless(view, projectilesOnly = true)) else listOf(Harmless(view, projectilesOnly = true))
            Switch.RAIDS -> listOf(Raids(view))
            Switch.SILENT_CONTAINERS -> listOf(SilentContainers(view, server))
            Switch.INVULNERABLE -> listOf(Harmless(view, projectilesOnly = false))
            else -> listOf(Chat(view))
        }
        for (listener in listeners) server.pluginManager.registerEvents(listener, plugin)
        return Unregister(listeners)
    }

    private class Unregister(private val listeners: List<Listener>) : AutoCloseable {
        override fun close() {
            for (listener in listeners) HandlerList.unregisterAll(listener)
        }
    }
}
