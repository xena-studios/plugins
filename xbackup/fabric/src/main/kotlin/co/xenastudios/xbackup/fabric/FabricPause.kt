package co.xenastudios.xbackup.fabric

import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.xbackup.AutosaveGate
import co.xenastudios.xbackup.Pauses
import co.xenastudios.xbackup.backup.Backup
import net.minecraft.server.level.ServerLevel
import kotlin.time.Duration.Companion.milliseconds

/**
 * The server's part of a backup on Fabric, as `/save-off` and `/save-all flush` do it:
 * - **Pause:** `ServerLevel.noSave` on every level, which stops the autosave.
 * - **Flush:** the players saved, then `MinecraftServer.saveAllChunks(flush = true, force = true)`
 *   on the server thread, which writes every changed chunk (entities too) and `level.dat`, and
 *   waits for the region writers before it returns, on every line from 1.20.1.
 * - **Resume** always, from `finally`, from a watchdog if a backup hangs, and as the server stops.
 *
 * A chunk the server unloads while saving is off can still be written; the backup's strict pass
 * reads each chunk whole or reads it again, so the snapshot stays consistent.
 */
internal class FabricPause(
    private val platform: FabricPlatform,
    private val maxPauseMillis: Long,
    private val onWatchdog: () -> Unit,
) : Backup.Pause {
    private val gate = AutosaveGate(NoSave)

    @Volatile
    private var watchdog: Task? = null

    private object NoSave : AutosaveGate.Switch<ServerLevel> {
        override fun isOn(world: ServerLevel): Boolean = !world.noSave

        override fun set(world: ServerLevel, on: Boolean) {
            world.noSave = !on
        }
    }

    override fun pause(): Long {
        gate.open()
        watchdog = platform.scheduler.run(Where.Async, delay = maxPauseMillis.milliseconds) {
            if (gate.isWanted) {
                platform.log.warn("A backup kept autosave off for longer than performance.max-pause, so autosave is back on and that backup was abandoned. Nothing was lost.")
                onWatchdog()
                resume()
            }
        }
        // One task on the server thread: saving off, players saved, everything flushed. If the
        // backup gave up waiting before it ran, it does nothing at all.
        return Pauses.onGlobal(platform.scheduler, 120) {
            val server = platform.server ?: return@onGlobal 0L
            val start = System.nanoTime()
            gate.turnOff(server.allLevels.toList()) ?: return@onGlobal 0L
            server.playerList.saveAll()
            server.saveAllChunks(true, true, true)
            (System.nanoTime() - start) / 1_000_000
        }
    }

    /** Saving back on for every level this pause turned off. Any thread, any number of times. */
    override fun resume() {
        watchdog?.cancel()
        gate.close()
    }
}
