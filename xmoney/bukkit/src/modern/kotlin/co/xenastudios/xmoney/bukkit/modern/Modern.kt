package co.xenastudios.xmoney.bukkit.modern

import java.util.UUID
import org.bukkit.Bukkit

// What only newer servers have, compiled against Paper's API. Called only once a check at startup
// found it (BukkitMoneyServer), so an old server never loads it.

/** Paper 1.16 on: the uuid of a player the server has cached by [name] (its user cache), never the disk or the web. */
internal fun cachedPlayer(name: String): UUID? = Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
