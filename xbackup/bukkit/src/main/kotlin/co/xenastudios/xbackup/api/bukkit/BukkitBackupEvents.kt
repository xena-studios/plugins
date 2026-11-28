package co.xenastudios.xbackup.api.bukkit

import co.xenastudios.xbackup.api.BackupSnapshot
import co.xenastudios.xbackup.api.event.BackupFailedEvent
import co.xenastudios.xbackup.api.event.BackupFinishedEvent
import co.xenastudios.xbackup.api.event.BackupStartEvent
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * [BackupStartEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way: a
 * backup is about to start. Cancelling it skips that backup. On the main thread (the global
 * thread on Folia). 1.x's `BackupStartEvent`, by its new name.
 *
 * @since 2.0.0
 */
public class BukkitBackupStartEvent(private val event: BackupStartEvent) : Event(), Cancellable {
    /** Why it runs: `scheduled`, `manual`, or what a plugin asked with. */
    public fun getReason(): String = event.reason
    public fun getEvent(): BackupStartEvent = event
    override fun isCancelled(): Boolean = event.isCancelled
    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [BackupFinishedEvent] as a Bukkit event: a backup is stored. Asynchronous: on xBackup's own thread. @since 2.0.0 */
public class BukkitBackupFinishedEvent(private val event: BackupFinishedEvent) : Event(true) {
    public fun getSnapshot(): BackupSnapshot = event.snapshot
    public fun getEvent(): BackupFinishedEvent = event
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [BackupFailedEvent] as a Bukkit event: a backup failed and nothing was stored for it. Asynchronous. @since 2.0.0 */
public class BukkitBackupFailedEvent(private val event: BackupFailedEvent) : Event(true) {
    public fun getReason(): String = event.reason
    public fun getMessage(): String = event.message
    public fun getEvent(): BackupFailedEvent = event
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
