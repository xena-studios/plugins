package co.xenastudios.xbans.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import kotlin.jvm.JvmField

/**
 * A punishment is about to be made, by a command or a plugin, after every check has passed and
 * before it's saved. Cancel it to stop it; the sender is told another plugin stopped it.
 *
 * Fired off the server's threads, only when something listens. On Bukkit it's also
 * `co.xenastudios.xbans.api.bukkit.BukkitPunishEvent`, which replaces 1.x's `PunishEvent`.
 *
 * @since 1.0.0
 */
public class PunishEvent(
    public val type: PunishmentType,
    /** The player, or `null` for an IP no account is known for. */
    public val target: PlayerRef?,
    public val targetName: String,
    public val reason: String,
    /** How long, in milliseconds; 0 for permanent. */
    public val durationMillis: Long,
    /** Who's making it, or `null` for the console or a plugin. */
    public val staff: PlayerRef?,
    public val staffName: String,
    public val silent: Boolean,
    public val ipBased: Boolean,
    /** [Punishment.GLOBAL] or a server's name. */
    public val scope: String,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<PunishEvent> = EventType.of("xbans:punish")
    }
}

/**
 * A punishment was made and saved, here or (with [fromOtherServer]) on another server of the
 * network. Off the server's threads, only when something listens. On Bukkit it's also
 * `co.xenastudios.xbans.api.bukkit.BukkitPunishedEvent`.
 *
 * @since 1.0.0
 */
public class PunishedEvent(public val punishment: Punishment, public val fromOtherServer: Boolean) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<PunishedEvent> = EventType.of("xbans:punished")
    }
}

/**
 * A punishment is about to be lifted (`/unban`, `/unmute`, `/unwarn`, or the API), after the
 * checks of who may lift it. Cancel it to keep it in force. Off the server's threads. On Bukkit
 * it's also `co.xenastudios.xbans.api.bukkit.BukkitPardonEvent`.
 *
 * @since 2.0.0
 */
public class PardonEvent(
    /** What's being lifted, as it is before. */
    public val punishment: Punishment,
    public val staff: PlayerRef?,
    public val staffName: String,
    public val reason: String,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<PardonEvent> = EventType.of("xbans:pardon")
    }
}

/**
 * A punishment was lifted, here or on another server. [punishment] has its
 * [Punishment.removal]. 1.x's `RevokedEvent`. On Bukkit it's also
 * `co.xenastudios.xbans.api.bukkit.BukkitPardonedEvent`.
 *
 * @since 1.0.0
 */
public class PardonedEvent(public val punishment: Punishment, public val fromOtherServer: Boolean) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<PardonedEvent> = EventType.of("xbans:pardoned")
    }
}

/**
 * A player was turned away as they tried to join: by [ban], or with no ban (`null`) because
 * xBans hadn't read its bans yet and turns everyone away until it has. [proxy] is whether the
 * proxy turned them away, before they reached any server.
 *
 * Not cancellable, on purpose: a ban another plugin could wave through is a ban something gets
 * round. A player who must never be banned gets `xbans.exempt`, which stops the ban being made.
 * Fired off the server's threads, only when something listens. On Bukkit it's also
 * `co.xenastudios.xbans.api.bukkit.BukkitLoginDeniedEvent`.
 *
 * @since 2.0.0
 */
public class LoginDeniedEvent(
    public val player: PlayerRef,
    public val name: String,
    public val ban: Punishment?,
    public val proxy: Boolean,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<LoginDeniedEvent> = EventType.of("xbans:login_denied")
    }
}

/**
 * Staff are about to be told something: a punishment's announcement, a banned player trying to
 * join, an alt joining, or a plugin's [co.xenastudios.xbans.api.XBansApi.notifyStaff]. [recipients]
 * are the online players who'll hear it; take players out (a vanish plugin hiding who did it), or
 * cancel it so only the console hears it. On the server's global thread (the plugin's own thread on
 * a proxy).
 *
 * @since 2.0.0
 */
public class StaffNotifyEvent(
    /** What they'll read, as plain text. */
    public val message: String,
    /** Whether it's silent: for staff with `xbans.notify.silent` only. */
    public val silent: Boolean,
    /** Who'll hear it; change it to change that. */
    public val recipients: MutableList<PlayerRef>,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<StaffNotifyEvent> = EventType.of("xbans:staff_notify")
    }
}
