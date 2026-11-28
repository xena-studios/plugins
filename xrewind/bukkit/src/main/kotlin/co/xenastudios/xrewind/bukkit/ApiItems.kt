package co.xenastudios.xrewind.bukkit

import co.xenastudios.core.api.WorldRef
import co.xenastudios.xrewind.XRewindPlugin
import co.xenastudios.xrewind.api.Actor
import org.bukkit.Location
import org.bukkit.inventory.ItemStack

/** The running xRewind's own item format, for `BukkitRewind.logContainerChange`. */
internal object ApiItems {
    fun log(actor: Actor, location: Location, item: ItemStack, amount: Int) {
        val (plugin, server) = XRewindBukkit.running ?: throw IllegalStateException("xRewind isn't enabled")
        val world = location.world ?: throw IllegalArgumentException("The location has no world")
        val one = item.clone().apply { this.amount = 1 }
        plugin.logContainerItem(actor, WorldRef.of(world.name), location.blockX, location.blockY, location.blockZ, item.type.key.toString(), server.blocks.bytes(one), amount)
    }
}
