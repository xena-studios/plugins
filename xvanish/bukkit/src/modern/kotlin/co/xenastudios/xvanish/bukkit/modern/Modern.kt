package co.xenastudios.xvanish.bukkit.modern

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xvanish.VanishView
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockReceiveGameEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.raid.RaidTriggerEvent
import org.bukkit.plugin.Plugin

// What only newer servers have, compiled against Paper's API. Each is used only once a check at
// startup has found what it needs (BukkitVanishServer.Paths), so an old server never loads it.

/** 1.13 on: hides [target] from [viewer] for this [plugin] alone, so another plugin's hiding isn't undone. */
internal fun hide(viewer: Player, plugin: Plugin, target: Player) {
    viewer.hidePlayer(plugin, target)
}

internal fun show(viewer: Player, plugin: Plugin, target: Player) {
    viewer.showPlayer(plugin, target)
}

/** 1.19.3 on: [player] hidden from everyone by default, or seen by everyone again. */
internal fun visibleByDefault(player: Player, visible: Boolean) {
    if (player.isVisibleByDefault != visible) player.isVisibleByDefault = visible
}

/** 1.9 on: whether players and mobs push [player]. */
internal fun collidable(player: Player, collidable: Boolean) {
    player.isCollidable = collidable
}

/** 1.9 on: a boss bar for [player] alone, with [title] as `§` text. */
internal fun bossBar(server: Server, player: Player, title: String): Any =
    server.createBossBar(title, BarColor.PURPLE, BarStyle.SOLID).also { it.addPlayer(player) }

internal fun removeBossBar(bar: Any) {
    (bar as BossBar).removeAll()
}

/** Paper: whether [player] makes mobs spawn around them. */
internal fun affectsSpawning(player: Player, affects: Boolean) {
    player.affectsSpawning = affects
}

/** 1.21.6 on: the range other players' locator bars show [player] from; returns what it was. */
internal fun locatorRange(player: Player, range: Double?): Double? {
    val attribute = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE) ?: return null
    val was = attribute.baseValue
    if (range != null) attribute.baseValue = range
    return was
}

/** Paper 1.16.5 on: a vanished player's advancements aren't announced. */
internal class Advancements(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun on(event: PlayerAdvancementDoneEvent) {
        if (!view.nobody && view.isVanished(event.player.ref)) event.message(null)
    }
}

/** 1.17 on: sculk sensors and shriekers don't hear a vanished player. */
internal class Sculk(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun on(event: BlockReceiveGameEvent) {
        if (view.nobody) return
        val player = event.entity as? Player ?: return
        if (view.isVanished(player.ref)) event.isCancelled = true
    }
}

/** 1.14 on: a vanished player doesn't start a raid. */
internal class Raids(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun on(event: RaidTriggerEvent) {
        if (!view.nobody && view.isVanished(event.player.ref)) event.isCancelled = true
    }
}

/**
 * Projectiles pass through a vanished player where the server lets the hit be cancelled (Paper,
 * and Spigot from 1.20.4). Elsewhere the damage listener stops the harm.
 */
internal class Projectiles(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun on(event: ProjectileHitEvent) {
        if (view.nobody) return
        val player = event.hitEntity as? Player ?: return
        // Checked when it runs: before 1.20.4 the event isn't Cancellable.
        val cancellable = event as Any
        if (cancellable is Cancellable && view.isVanished(player.ref)) cancellable.isCancelled = true
    }
}

/** 1.12 on: a vanished player doesn't pick up items (or, on Paper, experience) unless they chose to. */
internal class Pickup(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun on(event: EntityPickupItemEvent) {
        if (view.nobody) return
        val player = event.entity as? Player ?: return
        val ref = player.ref
        if (view.isVanished(ref) && !view.picksUp(ref)) event.isCancelled = true
    }
}

/** Paper 1.12.2 on: nor experience. */
internal class Experience(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun on(event: com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent) {
        if (view.nobody) return
        val ref = event.player.ref
        if (view.isVanished(ref) && !view.picksUp(ref)) event.isCancelled = true
    }
}
