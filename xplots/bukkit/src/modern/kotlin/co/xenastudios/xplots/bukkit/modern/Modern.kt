package co.xenastudios.xplots.bukkit.modern

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.resolve
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Sign
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

// What Paper and newer servers add, each used only where BukkitPlotServer.Paths found it. Each in
// an object of its own: a server without Adventure (Spigot) can't verify a class that hands
// Adventure's types around, even in a method it never calls.

/** Paper 1.16.5 on: an action bar, from the text as `§` codes. */
internal object AdventureBar {
    fun send(player: Player, legacy: String) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacy))
    }
}

/** Paper: chunks loaded off the server thread, and kept with a ticket (1.14 on) while a plot job works on them. */
internal object Chunks {
    fun load(plugin: org.bukkit.plugin.Plugin, world: World, x: Int, z: Int, tickets: Boolean): Pending<Boolean> {
        val out = newPending<Boolean>()
        world.getChunkAtAsync(x, z, true).whenComplete { chunk, error ->
            if (error != null || chunk == null) {
                out.resolve(false)
                return@whenComplete
            }
            if (tickets) chunk.addPluginChunkTicket(plugin)
            out.resolve(true)
        }
        return out
    }

    fun release(plugin: org.bukkit.plugin.Plugin, world: World, x: Int, z: Int) {
        if (world.isChunkLoaded(x, z)) world.getChunkAt(x, z).removePluginChunkTicket(plugin)
    }
}

/** Paper: a teleport that doesn't block the server while the destination loads, and Folia's only kind. */
internal object Teleports {
    fun teleport(entity: Entity, to: Location) {
        entity.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN)
    }
}

/** 1.20 on: a sign players can't edit. */
internal object Signs {
    fun wax(sign: Sign) {
        sign.isWaxed = true
    }
}

/** 1.14 on: items' persistent data (the wand's mark) and custom model data (a coin's). */
internal object Marks {
    fun mark(meta: ItemMeta, key: NamespacedKey) {
        meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
    }

    fun marked(meta: ItemMeta, key: NamespacedKey): Boolean = meta.persistentDataContainer.has(key, PersistentDataType.BYTE)

    @Suppress("DEPRECATION")
    fun modelData(meta: ItemMeta): Int = if (meta.hasCustomModelData()) meta.customModelData else 0

    @Suppress("DEPRECATION")
    fun setModelData(meta: ItemMeta, value: Int) {
        meta.setCustomModelData(value)
    }
}

/** Paper: a player who's played here, from the server's cache, without a lookup. */
internal object Offline {
    fun cached(server: Server, name: String): OfflinePlayer? = server.getOfflinePlayerIfCached(name)
}

/** Paper: where a falling block started, so sand landing in its own plot is part of building there. */
internal object Falling {
    fun origin(entity: Entity): Location? = (entity as? org.bukkit.entity.FallingBlock)?.origin
}
