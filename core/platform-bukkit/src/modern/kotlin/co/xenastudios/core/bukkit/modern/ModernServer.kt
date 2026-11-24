package co.xenastudios.core.bukkit.modern

import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID

/*
 * Bukkit calls newer than 1.8.8, for servers a class check at startup has found have them.
 */

/** Any entity by UUID, players first: `Server.getEntity(UUID)` arrived in 1.11. */
internal fun Server.entityById(id: UUID): Entity? = getPlayer(id) ?: getEntity(id)

/** The locale the client sent, `en_us`: `Player.getLocale()` arrived in 1.12, and Paper prefers `locale()`, which Spigot lacks. */
@Suppress("DEPRECATION")
internal fun Player.clientLocale(): String? = locale
