package co.xenastudios.xbackup.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.text.Component
import co.xenastudios.xbackup.BackupServer
import co.xenastudios.xbackup.JvmServer
import co.xenastudios.xbackup.Pauses
import co.xenastudios.xbackup.XBackupPlugin
import co.xenastudios.xbackup.backup.Backup
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.EventExecutor
import java.nio.file.Files
import java.nio.file.Path

/** The server, as xBackup's plugin and engine see it on Bukkit. */
internal class BukkitBackupServer(private val platform: BukkitPlatform) : BackupServer, JvmServer {
    private val server = platform.plugin.server

    @Volatile
    var xbackup: XBackupPlugin? = null

    override fun broadcast(draw: (PlayerRef?) -> Component?) {
        for (player in server.onlinePlayers) draw(player.uniqueId.toPlayerRef())?.let { platform.text.send(player, it) }
        draw(null)?.let { platform.text.send(server.consoleSender, it) }
    }

    override val anyoneOnline: Boolean get() = server.onlinePlayers.isNotEmpty()

    override fun watchJoins(joined: () -> Unit): AutoCloseable {
        val handler = object : Listener {}
        server.pluginManager.registerEvent(PlayerJoinEvent::class.java, handler, EventPriority.MONITOR, EventExecutor { _, _ -> joined() }, platform.plugin)
        return AutoCloseable { HandlerList.unregisterAll(handler) }
    }

    /** What another plugin registered through the API goes as it's disabled, as 1.x's did. */
    fun watchDisables(): AutoCloseable {
        val handler = object : Listener {}
        server.pluginManager.registerEvent(PluginDisableEvent::class.java, handler, EventPriority.MONITOR, EventExecutor { _, event ->
            if (event is PluginDisableEvent) xbackup?.forgetOwner(event.plugin.name)
        }, platform.plugin)
        return AutoCloseable { HandlerList.unregisterAll(handler) }
    }

    /**
     * Every loaded world's save folder: never `World#getWorldFolder()` alone, which on 26.x is a
     * dimension's folder inside the save folder; walking up to the folder with `level.dat` gives
     * the save folder on both layouts (1.21.11's `world_nether` has its own). 1.x's way.
     */
    override fun worldFolders(): List<Path> = Pauses.onGlobal(platform.scheduler, 60) {
        val container = server.worldContainer.toPath().toAbsolutePath().normalize()
        val folders = LinkedHashSet<Path>()
        for (world in server.worlds) {
            val folder = world.worldFolder.toPath().toAbsolutePath().normalize()
            var save: Path? = folder
            while (save != null && !Files.exists(save.resolve("level.dat")) && save.startsWith(container) && save != container) save = save.parent
            folders.add(if (save != null && Files.exists(save.resolve("level.dat"))) save else folder)
        }
        folders.toList()
    }

    override fun pause(maxPauseMillis: Long, onWatchdog: () -> Unit): Backup.Pause = BukkitPause(platform, maxPauseMillis, ::worldFolders, onWatchdog)

    override val pluginsFolder: Path get() = platform.plugin.dataFolder.toPath().toAbsolutePath().parent

    override fun enabled(owner: String): Boolean = server.pluginManager.getPlugin(owner)?.isEnabled ?: true
}
