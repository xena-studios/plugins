package co.xenastudios.xmoney.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xmoney.KnownPlayer
import co.xenastudios.xmoney.MoneyServer
import org.bukkit.Bukkit

/**
 * What xMoney's shared code asks of a Bukkit-family server, 1.8.8 on. Names come from the
 * family's player cache, then Paper's user cache where it has one (1.16 on), and last the
 * server's list of players who have played, read off its threads: never the web.
 */
internal class BukkitMoneyServer(private val platform: BukkitPlatform, private val cache: PlayerCache) : MoneyServer {
    private val bukkit = platform.plugin.server

    // Paper's cached offline players, found once: Spigot has only the lookup that asks Mojang.
    private val paperCache = try {
        bukkit.javaClass.getMethod("getOfflinePlayerIfCached", String::class.java)
        true
    } catch (e: NoSuchMethodException) {
        false
    }

    override fun send(player: PlayerRef, message: Component) {
        player.online(bukkit)?.let { platform.text.send(it, message) }
    }

    override fun isOnline(player: PlayerRef): Boolean = player.online(bukkit) != null

    override fun onlineNames(): List<String> = bukkit.onlinePlayers.map { it.name }

    /** The uuid of a player the server knows by [name] from memory: online, the family's cache, Paper's. Any thread. */
    fun cachedId(name: String): PlayerRef? {
        bukkit.getPlayerExact(name)?.let { return it.ref }
        cache.find(name)?.let { return it.player }
        if (paperCache) co.xenastudios.xmoney.bukkit.modern.cachedPlayer(name)?.let { return PlayerRef.parseOrNull(it.toString()) }
        return null
    }

    override fun known(name: String): Pending<KnownPlayer?> {
        bukkit.getPlayerExact(name)?.let { return pendingOf(KnownPlayer(it.ref, it.name)) }
        cache.find(name)?.let { return pendingOf(KnownPlayer(it.player, it.name)) }
        // Every player who has played, from the worlds' files: disk work, so off the server's threads.
        val found = newPending<KnownPlayer?>()
        platform.scheduler.run(Where.Async) {
            val player = try {
                @Suppress("DEPRECATION")
                bukkit.offlinePlayers.firstOrNull { it.name.equals(name, ignoreCase = true) }
            } catch (e: RuntimeException) {
                null
            }
            found.resolve(player?.let { KnownPlayer(it.ref, it.name ?: name) })
        }
        return found
    }

    override fun cachedName(player: PlayerRef): String? = player.online(bukkit)?.name ?: cache[player]?.name

    /** Whether the caller is on one of the server's game threads (the main thread, or a Folia region's). */
    fun isServerThread(): Boolean = Bukkit.isPrimaryThread()
}
