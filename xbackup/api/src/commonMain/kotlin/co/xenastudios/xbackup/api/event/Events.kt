package co.xenastudios.xbackup.api.event

import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xbackup.api.BackupSnapshot
import kotlin.jvm.JvmField

/**
 * A backup is about to start: cancel it to skip that backup, such as during an event. On the
 * global thread (the main thread on Paper and Spigot), only when something listens. On Bukkit
 * it's also `co.xenastudios.xbackup.api.bukkit.BukkitBackupStartEvent`.
 *
 * @since 2.0.0
 */
public class BackupStartEvent(
    /** Why it runs: `scheduled`, `manual`, or what a plugin asked with. */
    public val reason: String,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<BackupStartEvent> = EventType.of("xbackup:start")
    }
}

/**
 * A backup is stored. On xBackup's own thread. On Bukkit also `BukkitBackupFinishedEvent`.
 *
 * @since 2.0.0
 */
public class BackupFinishedEvent(public val snapshot: BackupSnapshot) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<BackupFinishedEvent> = EventType.of("xbackup:finished")
    }
}

/**
 * A backup failed; nothing was stored for it. On xBackup's own thread. On Bukkit also
 * `BukkitBackupFailedEvent`.
 *
 * @since 2.0.0
 */
public class BackupFailedEvent(
    public val reason: String,
    /** What went wrong, for people. */
    public val message: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<BackupFailedEvent> = EventType.of("xbackup:failed")
    }
}
