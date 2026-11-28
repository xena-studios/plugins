package co.xenastudios.xbackup.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.PlayerJoinEvent
import co.xenastudios.xbackup.BackupServer
import co.xenastudios.xbackup.JvmServer
import co.xenastudios.xbackup.backup.Backup
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path

/** The server, as xBackup's plugin and engine see it on Fabric. */
internal class FabricBackupServer(private val platform: FabricPlatform) : BackupServer, JvmServer {
    override fun broadcast(draw: (PlayerRef?) -> Component?) {
        val server = platform.server ?: return
        server.execute {
            for (player in server.playerList.players) {
                draw(PlayerRef.parseOrNull(player.uuid.toString())!!)?.let { player.sendSystemMessage(MinecraftRenderer.draw(it)) }
            }
            draw(null)?.let { server.sendSystemMessage(MinecraftRenderer.draw(it)) }
        }
    }

    override val anyoneOnline: Boolean get() = platform.server?.playerList?.playerCount?.let { it > 0 } ?: false

    override fun watchJoins(joined: () -> Unit): AutoCloseable {
        val subscription = platform.events.subscribe(PlayerJoinEvent.TYPE) { joined() }
        return AutoCloseable { subscription.cancel() }
    }

    /** The world's save folder: one on Fabric, with every dimension inside it. */
    override fun worldFolders(): List<Path> {
        val server = platform.server ?: return emptyList()
        return listOf(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize())
    }

    override fun pause(maxPauseMillis: Long, onWatchdog: () -> Unit): Backup.Pause = FabricPause(platform, maxPauseMillis, onWatchdog)

    override val pluginsFolder: Path get() = FabricLoader.getInstance().gameDir.resolve("mods").toAbsolutePath().normalize()

    // Mods can't be turned off while the server runs.
    override fun enabled(owner: String): Boolean = true
}
