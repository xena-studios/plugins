package co.xenastudios.xbackup.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xbackup.api.bukkit.BukkitBackupFailedEvent
import co.xenastudios.xbackup.api.bukkit.BukkitBackupFinishedEvent
import co.xenastudios.xbackup.api.bukkit.BukkitBackupStartEvent
import co.xenastudios.xbackup.api.event.BackupFailedEvent
import co.xenastudios.xbackup.api.event.BackupFinishedEvent
import co.xenastudios.xbackup.api.event.BackupStartEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too. Bridged as xBackup enables, but the Bukkit events
 * are named only in [Fire], so neither loads until the first backup with a listener there.
 */
internal object EventBridge {
    fun bridge(server: Server, bus: SimpleEventBus) {
        bus.bridge(BackupStartEvent.TYPE, forward = Forward(server, 0))
        bus.bridge(BackupFinishedEvent.TYPE, forward = Forward(server, 1))
        bus.bridge(BackupFailedEvent.TYPE, forward = Forward(server, 2))
    }

    private class Forward(private val server: Server, private val kind: Int) : (Event) -> Unit {
        override fun invoke(event: Event) {
            Fire.fire(server, kind, event)
        }
    }

    private object Fire {
        fun fire(server: Server, kind: Int, event: Event) {
            when (kind) {
                0 -> if (BukkitBackupStartEvent.getHandlerList().registeredListeners.isNotEmpty()) server.pluginManager.callEvent(BukkitBackupStartEvent(event as BackupStartEvent))
                1 -> if (BukkitBackupFinishedEvent.getHandlerList().registeredListeners.isNotEmpty()) server.pluginManager.callEvent(BukkitBackupFinishedEvent(event as BackupFinishedEvent))
                else -> if (BukkitBackupFailedEvent.getHandlerList().registeredListeners.isNotEmpty()) server.pluginManager.callEvent(BukkitBackupFailedEvent(event as BackupFailedEvent))
            }
        }
    }
}
