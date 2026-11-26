package co.xenastudios.xteleport.bukkit.modern

import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Waterlogged
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.concurrent.CompletableFuture

// What only newer servers have, compiled against Paper's API. Each function is called only once
// a check at startup has found what it uses (BukkitTeleportServer.Paths), so an old server never
// loads this file's classes.

/** Paper and Folia: teleports [player] without blocking, as a command does; Folia allows nothing else. */
internal fun teleportAsync(player: Player, to: Location, done: (Boolean) -> Unit) {
    player.teleportAsync(to, TeleportCause.COMMAND).whenComplete { moved, error -> done(error == null && moved == true) }
}

/** Paper 1.13 on: the chunk, loaded (or generated, with [generate]) off the server thread. */
internal fun chunkAsync(world: World, x: Int, z: Int, generate: Boolean): CompletableFuture<Boolean> =
    world.getChunkAtAsync(x, z, generate).handle { chunk, error -> error == null && chunk != null }

/** 1.13 on: whether blocks of [type] can hold water. */
internal fun waterloggable(type: Material): Boolean = try {
    type.isBlock && type.createBlockData() is Waterlogged
} catch (e: Exception) {
    false
}

/** 1.13 on: whether [block] holds water now. */
internal fun waterlogged(block: Block): Boolean = (block.blockData as? Waterlogged)?.isWaterlogged == true

/** 1.15 on: the highest block that stops movement (leaves count, grass doesn't). */
internal fun highest(world: World, x: Int, z: Int): Int = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)

/** 1.16 on: the biome as Minecraft names it, without its namespace. */
internal fun biome(world: World, x: Int, y: Int, z: Int): String = world.getBiome(x, y, z).key.key

/** 1.17 on: the world's floor, roof and logical height. */
internal fun minHeight(world: World): Int = world.minHeight

internal fun logicalHeight(world: World): Int = world.logicalHeight

internal fun hasCeiling(world: World): Boolean = world.hasCeiling()

/**
 * The action bar, each way in a class of its own: a server without Adventure (Spigot) can't
 * verify a class that hands Adventure's types around, even in a method it never calls.
 */
internal object AdventureBar {
    /** Paper 1.16.5 on: an action bar, from the text as `§` codes. */
    fun send(player: Player, legacy: String) {
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(legacy))
    }
}

internal object SpigotBar {
    /** Spigot 1.9 on (and 1.8.8's own Bungee chat): an action bar. */
    @Suppress("DEPRECATION")
    fun send(player: Player, legacy: String) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, *net.md_5.bungee.api.chat.TextComponent.fromLegacyText(legacy))
    }
}

/**
 * Paper: the block of the player's bed or respawn anchor as they set it, without loading its chunk
 * to check it (the teleport does, in the background). Deprecated since 1.21.5, not for removal, for
 * a method that loads the chunk on the calling thread, which is what mustn't happen here.
 */
@Suppress("DEPRECATION")
internal fun potentialRespawn(player: Player): Location? = player.potentialRespawnLocation

/** Paper 1.14 to 1.21.4: the same, for beds only. */
@Suppress("DEPRECATION")
internal fun potentialBed(player: Player): Location? = player.potentialBedLocation

/** 1.16 on: a respawn at an anchor. */
internal fun anchorSpawn(event: PlayerRespawnEvent): Boolean = event.isAnchorSpawn

/** Paper 1.19.4 on: whether a respawn follows a death, not leaving the End. */
internal fun deathRespawn(event: PlayerRespawnEvent): Boolean = event.respawnReason == PlayerRespawnEvent.RespawnReason.DEATH

/**
 * Paper 1.21.9 on: placing a joining player before they're in the world, off the server's
 * threads, so arriving at the spawn is no teleport at all. [place] reads only memory.
 */
internal class AsyncSpawn(private val place: (java.util.UUID, Boolean) -> Location?) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    fun onSpawnLocation(event: io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent) {
        place(event.connection.profile.id ?: return, event.isNewPlayer)?.let { event.spawnLocation = it }
    }
}
