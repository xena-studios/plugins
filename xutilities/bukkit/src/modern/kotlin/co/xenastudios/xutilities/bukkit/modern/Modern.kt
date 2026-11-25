package co.xenastudios.xutilities.bukkit.modern

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.Damageable

// What only newer servers have, compiled against Paper's API. Each function is called only once
// a check at startup has found what it uses (BukkitXServer.Paths), so an old server never loads
// this class.

/** Folia: whether the current thread owns [entity]'s region, so its inventory may be touched. */
internal fun ownsRegion(entity: Entity): Boolean = Bukkit.isOwnedByCurrentRegion(entity)

/** Paper and Folia: teleports [player] without blocking, as a command does; Folia allows nothing else. */
internal fun teleportAsync(player: Player, to: Location, done: (Boolean) -> Unit) {
    player.teleportAsync(to, TeleportCause.COMMAND).thenAccept { moved -> done(moved == true) }
}

/** 1.13 on. */
internal fun setRotation(player: Player, yaw: Float, pitch: Float) {
    player.setRotation(yaw, pitch)
}

/** Spigot 1.17 on (Paper earlier). */
internal fun ping(player: Player): Int = player.ping

/** 1.13 on. */
internal fun passable(block: Block): Boolean = block.isPassable

/** 1.9 on: the off-hand. */
internal fun clearOffHand(inventory: PlayerInventory) {
    inventory.setItemInOffHand(null)
}

/** 1.9 on: the main hand. */
internal fun mainHand(inventory: PlayerInventory): ItemStack? = inventory.itemInMainHand

internal fun setMainHand(inventory: PlayerInventory, item: ItemStack) {
    inventory.setItemInMainHand(item)
}

/** 1.13 on: an item's damage is in its meta. Whether it was damaged, and so was repaired. */
internal fun repairMeta(item: ItemStack): Boolean {
    val meta = item.itemMeta as? Damageable ?: return false
    if (!meta.hasDamage()) return false
    meta.damage = 0
    item.itemMeta = meta
    return true
}

/** The menus by name, as [MenuType] has them: Spigot and Paper 1.21.4 on. */
internal fun openMenu(player: Player, station: String) {
    val type = when (station) {
        "anvil" -> MenuType.ANVIL
        "craft" -> MenuType.CRAFTING
        "grinder" -> MenuType.GRINDSTONE
        "loom" -> MenuType.LOOM
        "smith" -> MenuType.SMITHING
        "stonecutter" -> MenuType.STONECUTTER
        else -> MenuType.CARTOGRAPHY_TABLE
    }
    // checkReachable(false): the menu stays open with no block nearby.
    type.builder().checkReachable(false).build(player).open()
}

/** Paper 1.14 to 1.21: its own methods for each menu, before MenuType. */
@Suppress("DEPRECATION")
internal fun openPaperMenu(player: Player, station: String) {
    when (station) {
        "anvil" -> player.openAnvil(null, true)
        "grinder" -> player.openGrindstone(null, true)
        "loom" -> player.openLoom(null, true)
        "smith" -> player.openSmithingTable(null, true)
        "stonecutter" -> player.openStonecutter(null, true)
        "cartography" -> player.openCartographyTable(null, true)
        else -> player.openWorkbench(null, true)
    }
}
