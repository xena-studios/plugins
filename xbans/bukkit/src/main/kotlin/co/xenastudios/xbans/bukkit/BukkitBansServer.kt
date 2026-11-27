package co.xenastudios.xbans.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.resolve
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xbans.BansServer
import co.xenastudios.xbans.Power
import org.bukkit.command.Command
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.time.ZoneId
import kotlin.uuid.toKotlinUuid

/**
 * xBans' [BansServer] on Bukkit, 1.8.8 to the newest: players, their addresses and permissions,
 * kicks on each player's own thread (Folia's), and the server's command map for mutes. Kick
 * screens are `§` text, which every Bukkit since 1.8.8 takes, with RGB from 1.16.
 */
internal class BukkitBansServer(private val platform: BukkitPlatform) : BansServer {
    private val server = platform.plugin.server
    private val zone = ZoneId.systemDefault()

    override fun online(): List<PlayerRef> = server.onlinePlayers.map { it.ref }

    override fun isOnline(player: PlayerRef): Boolean = player.online(server) != null

    override fun name(player: PlayerRef): String? = player.online(server)?.name

    override fun address(player: PlayerRef): String? = player.online(server)?.address?.address?.hostAddress

    override fun send(player: PlayerRef, message: Component) {
        player.online(server)?.let { platform.text.send(it, message) }
    }

    override fun console(message: Component) {
        platform.text.send(server.consoleSender, message)
    }

    /** [screen] as the `§` text a kick or a refused login takes. */
    fun legacy(screen: Component): String = platform.text.legacy.draw(screen)

    override fun kick(player: PlayerRef, screen: Component, quiet: Boolean) {
        val text = legacy(screen)
        platform.scheduler.run(Where.On(player.uuid)) { _ -> player.online(server)?.kickPlayer(text) }
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player.online(server)?.hasPermission(node) ?: false

    override fun weight(player: PlayerRef): Int = player.online(server)?.let(::weight) ?: 0

    /** The highest `xbans.weight.<n>` [player] has been given; 0 without one. Operators get none by default. */
    fun weight(player: Player): Int {
        var weight = 0
        for (info in player.effectivePermissions) {
            val name = info.permission.lowercase()
            if (info.value && name.startsWith(Power.WEIGHT_PREFIX)) {
                name.substring(Power.WEIGHT_PREFIX.length).toIntOrNull()?.let { weight = maxOf(weight, it) }
            }
        }
        return weight
    }

    override fun dispatch(line: String) {
        server.dispatchCommand(server.consoleSender, line)
    }

    // The server's command map, through CraftServer's own method: Bukkit's API has none before Paper's.
    private val commandMap: Any? by lazy {
        try {
            val method: Method = server.javaClass.getMethod("getCommandMap")
            method.invoke(server)
        } catch (e: ReflectiveOperationException) {
            null
        }
    }

    override fun commandNames(label: String): List<String>? {
        val map = commandMap as? org.bukkit.command.CommandMap ?: return null
        val command: Command = map.getCommand(label) ?: return null
        return listOf(command.name, command.label) + command.aliases
    }

    override fun lookup(name: String): Pending<Pair<PlayerRef, String>?> {
        val result = newPending<Pair<PlayerRef, String>?>()
        // The server's own lookup: its cache, Mojang's profiles, or an offline-mode server's uuid. It can block, so off the server's threads.
        platform.scheduler.run(Where.Async) { _ ->
            result.resolve(try {
                @Suppress("DEPRECATION")
                val found = server.getOfflinePlayer(name)
                found.uniqueId?.let { PlayerRef.of(it.toKotlinUuid()) to (found.name ?: name) }
            } catch (e: Exception) {
                null
            })
        }
        return result
    }

    override fun utcOffsetSeconds(epochMillis: Long): Int = zone.rules.getOffset(java.time.Instant.ofEpochMilli(epochMillis)).totalSeconds
}
