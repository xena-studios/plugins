@file:JvmName("BukkitRefs")

package co.xenastudios.core.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.toPlayerRef
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.Location as BukkitLocation

/*
 * Between Bukkit's objects and the core's references, which name players by UUID and worlds by
 * folder, so shared code never holds a server object (DECISIONS.md, "The platform layer"). From
 * Java, BukkitRefs.ref(player), BukkitRefs.toCore(location) and so on.
 */

/** The player, online or not, as the core names them. */
public val OfflinePlayer.ref: PlayerRef
    @JvmName("ref") get() = uniqueId.toPlayerRef()

/** The player, if they're online on [server]. */
public fun PlayerRef.online(server: Server): Player? = server.getPlayer(javaUuid)

/** The world, by its folder's name, which Bukkit keeps from one start to the next. */
public val World.ref: WorldRef
    @JvmName("ref") get() = WorldRef.of(name)

/** The world, if it's loaded on [server]. */
public fun WorldRef.loaded(server: Server): World? = server.getWorld(name)

/** The same place as the core sees it. Throws if it has no world, which only a Location made by hand lacks. */
public fun BukkitLocation.toCore(): Location {
    val world = requireNotNull(world) { "A location with no world has no place in a world" }
    return Location(world.ref, x, y, z, yaw, pitch)
}

/** The same place as Bukkit sees it, or `null` if its world isn't loaded on [server]. */
public fun Location.toBukkit(server: Server): BukkitLocation? =
    world.loaded(server)?.let { BukkitLocation(it, x, y, z, yaw, pitch) }
