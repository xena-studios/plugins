package co.xenastudios.xvanish.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xvanish.VanishView
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerListPingEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.Collections
import java.util.IdentityHashMap

// xVanish's listeners on Bukkit, one class per switch, registered only while its switch is on.
// Each starts by asking whether anyone's vanished, so with nobody vanished it returns at once.

/** Joins and quits: always on. The join is decided first, so every other plugin's canSee already knows. */
internal class Players(private val view: VanishView) : Listener {
    // Who joined vanished, between the two join handlers.
    private val joinedVanished = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<java.util.UUID, Boolean>())

    @EventHandler(priority = EventPriority.LOWEST)
    fun joining(event: PlayerJoinEvent) {
        val player = event.player
        if (view.joining(player.ref, player.name)) joinedVanished += player.uniqueId
    }

    // Last, so no other plugin puts the message back.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun joined(event: PlayerJoinEvent) {
        if (joinedVanished.remove(event.player.uniqueId)) event.joinMessage = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun quit(event: PlayerQuitEvent) {
        if (view.quitting(event.player.ref)) event.quitMessage = null
    }
}

/** The server list: vanished players aren't counted or in the sample (Bukkit's iterator takes them out of both). */
internal class ServerList(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    fun ping(event: ServerListPingEvent) {
        if (view.nobody) return
        try {
            val players = event.iterator()
            while (players.hasNext()) if (view.isVanished(players.next().ref)) players.remove()
        } catch (e: UnsupportedOperationException) {
            // A legacy ping, or a server that doesn't list players: nothing to take out.
        }
    }
}

/** A vanished player's death isn't broadcast. */
internal class Deaths(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun death(event: PlayerDeathEvent) {
        if (!view.nobody && view.isVanished(event.entity.ref)) event.deathMessage = null
    }
}

/** Mobs don't target a vanished player ([pickup] `false`); experience orbs don't fly to one ([pickup] `true`). */
internal class Targeting(private val view: VanishView, private val pickup: Boolean) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun target(event: EntityTargetEvent) {
        if (view.nobody) return
        val player = event.target as? Player ?: return
        val orb = event.entity is ExperienceOrb
        if (orb != pickup) return
        val ref = player.ref
        if (view.isVanished(ref) && !(orb && view.picksUp(ref))) event.isCancelled = true
    }
}

/** Before 1.12: a vanished player doesn't pick up items, unless they chose to. */
internal class OldPickup(private val view: VanishView) : Listener {
    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun pickup(event: PlayerPickupItemEvent) {
        if (view.nobody) return
        val ref = event.player.ref
        if (view.isVanished(ref) && !view.picksUp(ref)) event.isCancelled = true
    }
}

/** Pressure plates, tripwires, farmland and turtle eggs: what a player sets off by standing on it. */
internal class Physical(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun step(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL && !view.nobody && view.isVanished(event.player.ref)) event.isCancelled = true
    }
}

/** No damage and no hunger ([projectilesOnly] `false`), or only no harm from projectiles. */
internal class Harmless(private val view: VanishView, private val projectilesOnly: Boolean) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun damage(event: EntityDamageEvent) {
        if (view.nobody) return
        val player = event.entity as? Player ?: return
        if (projectilesOnly && (event as? EntityDamageByEntityEvent)?.damager !is Projectile) return
        if (view.isVanished(player.ref)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun hunger(event: FoodLevelChangeEvent) {
        if (projectilesOnly || view.nobody) return
        val player = event.entity as? Player ?: return
        if (event.foodLevel < player.foodLevel && view.isVanished(player.ref)) event.isCancelled = true
    }
}

/**
 * Silent containers: a vanished player opening a chest, barrel or shulker box gets a copy of it to
 * look at, so the lid doesn't move and nothing is heard; clicks in the copy change nothing.
 * Sneaking opens it as usual. An ender chest opens their own, which makes no sound.
 */
internal class SilentContainers(private val view: VanishView, private val server: Server) : Listener {
    private val copies = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Inventory, Boolean>()))

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    fun open(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || view.nobody) return
        val player = event.player
        if (player.isSneaking || !view.isVanished(player.ref)) return
        val block = event.clickedBlock ?: return
        val kind = kind(block.type)
        if (kind == NONE) return
        event.isCancelled = true
        if (kind == ENDER) {
            player.openInventory(player.enderChest)
            return
        }
        val real = (block.state as? InventoryHolder)?.inventory ?: return
        val copy = server.createInventory(null, ((real.size + 8) / 9).coerceIn(1, 6) * 9)
        copy.contents = real.contents.copyOf(copy.size).map { it?.clone() }.toTypedArray()
        copies += copy
        player.openInventory(copy)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun click(event: InventoryClickEvent) {
        if (copies.isNotEmpty() && event.view.topInventory in copies) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun drag(event: InventoryDragEvent) {
        if (copies.isNotEmpty() && event.view.topInventory in copies) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun close(event: InventoryCloseEvent) {
        if (copies.isNotEmpty()) copies -= event.inventory
    }

    private fun kind(type: Material): Int {
        val name = type.name
        return when {
            name == "ENDER_CHEST" -> ENDER
            name == "CHEST" || name == "TRAPPED_CHEST" || name == "BARREL" || name.endsWith("SHULKER_BOX") -> COPY
            else -> NONE
        }
    }

    private companion object {
        const val NONE = 0
        const val COPY = 1
        const val ENDER = 2
    }
}

/** A vanished player's first public chat message is held back (chat.confirm-public). */
internal class Chat(private val view: VanishView) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun chat(event: AsyncPlayerChatEvent) {
        if (!view.nobody && !view.mayChat(event.player.ref, event.message)) event.isCancelled = true
    }
}

