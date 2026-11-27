package co.xenastudios.xbans.api

import co.xenastudios.core.api.PlayerRef
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.time.Instant

/** What a punishment is. @since 1.0.0 */
public enum class PunishmentType {
    BAN,
    MUTE,
    WARN,
    KICK,
    ;

    /** Whether it lasts (bans, mutes, warnings) rather than happening once (kicks). */
    public val lasts: Boolean get() = this != KICK
}

/**
 * Who lifted a punishment, when and why: an unban, unmute or unwarn. The punishment stays in the
 * history with it.
 *
 * @since 1.0.0
 */
public class Removal(
    /** Who lifted it, or `null` for the console or a plugin. */
    public val staff: PlayerRef?,
    public val staffName: String,
    public val at: Instant,
    /** Why, as typed: empty when no reason was given. */
    public val reason: String,
) {
    override fun equals(other: Any?): Boolean =
        other is Removal && other.staff == staff && other.staffName == staffName && other.at == at && other.reason == reason

    override fun hashCode(): Int = ((staff.hashCode() * 31 + staffName.hashCode()) * 31 + at.hashCode()) * 31 + reason.hashCode()

    override fun toString(): String = "Removal($staffName, $at, $reason)"
}

/**
 * One punishment, as it's stored: punishments are never changed once made, only revoked
 * ([removal]). An IP punishment's address isn't here: the API never shows addresses.
 *
 * @since 1.0.0
 */
public class Punishment(
    /** Its number, as `/history` and `#id` show it. */
    public val id: Long,
    public val type: PunishmentType,
    /** The player, or `null` for an IP punishment no account is known for. */
    public val target: PlayerRef?,
    /** The player's name when it was made, or for an IP punishment with no account, half its address. */
    public val targetName: String,
    /** Whether it's on the player's IP (reaching every account on it), not only the account. */
    public val ipBased: Boolean,
    public val reason: String,
    /** Who made it, or `null` for the console, a plugin or an import. */
    public val staff: PlayerRef?,
    public val staffName: String,
    public val created: Instant,
    /** When it ends, or `null` for never (and for kicks). */
    public val expires: Instant?,
    /** [GLOBAL] for every server, or one server's name. */
    public val scope: String,
    /** Whether only staff were told. */
    public val silent: Boolean,
    /** The template it followed, or `null`. */
    public val template: String?,
    /** Who lifted it, or `null` while it hasn't been. */
    public val removal: Removal?,
) {
    /** Whether it lasts for ever. */
    public val isPermanent: Boolean get() = expires == null

    /** Whether it applies on every server. */
    public val isGlobal: Boolean get() = scope == GLOBAL

    /** Whether it's in force at [now]: it lasts, hasn't been lifted and hasn't ended. */
    public fun isActive(now: Instant): Boolean = type.lasts && removal == null && (expires == null || expires > now)

    /** The same punishment, lifted. */
    public fun withRemoval(removal: Removal?): Punishment =
        Punishment(id, type, target, targetName, ipBased, reason, staff, staffName, created, expires, scope, silent, template, removal)

    override fun equals(other: Any?): Boolean = other is Punishment && other.id == id && other.type == type &&
        other.target == target && other.targetName == targetName && other.ipBased == ipBased && other.reason == reason &&
        other.staff == staff && other.staffName == staffName && other.created == created && other.expires == expires &&
        other.scope == scope && other.silent == silent && other.template == template && other.removal == removal

    override fun hashCode(): Int = (id.hashCode() * 31 + type.hashCode()) * 31 + created.hashCode()

    override fun toString(): String = "Punishment(#$id $type $targetName, $reason, by $staffName, until ${expires ?: "never"}, $scope)"

    public companion object {
        /** The scope of a punishment that applies on every server. */
        public const val GLOBAL: String = "*"
    }
}

/**
 * A punishment for a plugin to make, through [XBansApi.punish]: what, whom and why, then
 * optionally how long, by whom and how. Immutable: each `with...` makes a copy.
 *
 * ```java
 * api.punish(PunishRequest.of(PunishmentType.BAN, player, "Cheating").withDurationMillis(86_400_000L).withSilent(true));
 * ```
 *
 * @since 1.0.0
 */
public class PunishRequest private constructor(
    public val type: PunishmentType,
    public val target: PlayerRef,
    public val reason: String,
    /** How long, in milliseconds, or 0 for permanent (kicks ignore it). */
    public val durationMillis: Long,
    /** Who's named as having made it, or `null` for the plugin ([staffName]). */
    public val staff: PlayerRef?,
    public val staffName: String,
    public val silent: Boolean,
    /** Told to the console only, not even to staff (`-S`). */
    public val extraSilent: Boolean,
    public val ipBased: Boolean,
    /** [Punishment.GLOBAL], a server's name, or `null` for the config's default. */
    public val scope: String?,
) {
    public fun withDurationMillis(millis: Long): PunishRequest = copy(durationMillis = maxOf(0, millis))

    @JvmOverloads
    public fun withStaff(staff: PlayerRef?, staffName: String = this.staffName): PunishRequest = copy(staff = staff, staffName = staffName)

    public fun withSilent(silent: Boolean): PunishRequest = copy(silent = silent)

    public fun withExtraSilent(extraSilent: Boolean): PunishRequest = copy(extraSilent = extraSilent)

    public fun withIpBased(ipBased: Boolean): PunishRequest = copy(ipBased = ipBased)

    public fun withScope(scope: String?): PunishRequest = copy(scope = scope)

    private fun copy(
        durationMillis: Long = this.durationMillis,
        staff: PlayerRef? = this.staff,
        staffName: String = this.staffName,
        silent: Boolean = this.silent,
        extraSilent: Boolean = this.extraSilent,
        ipBased: Boolean = this.ipBased,
        scope: String? = this.scope,
    ) = PunishRequest(type, target, reason, durationMillis, staff, staffName, silent, extraSilent, ipBased, scope)

    override fun toString(): String = "PunishRequest($type $target, $reason, ${durationMillis}ms, by $staffName)"

    public companion object {
        /** A permanent [type] of [target] for [reason], by the plugin, public, on the config's default scope. */
        @JvmStatic
        public fun of(type: PunishmentType, target: PlayerRef, reason: String): PunishRequest =
            PunishRequest(type, target, reason, 0, null, "Console", false, false, false, null)
    }
}

/** One page of a history, newest first. @since 1.0.0 */
public class HistoryPage(
    public val punishments: List<Punishment>,
    /** This page's number, from 1. */
    public val page: Int,
    public val pages: Int,
    /** How many punishments the whole history has. */
    public val total: Long,
) {
    override fun toString(): String = "HistoryPage($page/$pages of $total)"
}

/** An account that shared an IP with another, and when it was last seen on it. @since 1.0.0 */
public class Alt(public val player: PlayerRef, public val name: String, public val lastSeen: Instant) {
    override fun equals(other: Any?): Boolean = other is Alt && other.player == player && other.name == name && other.lastSeen == lastSeen

    override fun hashCode(): Int = player.hashCode()

    override fun toString(): String = "Alt($name, $lastSeen)"
}

/**
 * What became of [XBansApi.punish] or [XBansApi.pardon]: [DONE] with what was made or lifted,
 * or why not, as the command's sender would have been told ([message], in English).
 *
 * @since 2.0.0
 */
public class PunishResult(
    public val outcome: Outcome,
    /** What was made, or the first one lifted; `null` unless [outcome] is [Outcome.DONE]. */
    public val punishment: Punishment?,
    /** Why it wasn't done, in plain English; empty when it was. */
    public val message: String,
) {
    /** How it went. */
    public enum class Outcome {
        DONE,

        /** It couldn't go ahead: already banned for longer, outranked, nothing to lift... [message] says. */
        REFUSED,

        /** A listener cancelled [co.xenastudios.xbans.api.event.PunishEvent] or [co.xenastudios.xbans.api.event.PardonEvent]. */
        CANCELLED,

        /** The database couldn't save it, or isn't open yet. */
        FAILED,
    }

    override fun toString(): String = "PunishResult($outcome, $punishment, $message)"

    public companion object {
        @JvmStatic
        public fun done(punishment: Punishment): PunishResult = PunishResult(Outcome.DONE, punishment, "")

        @JvmField
        public val CANCELLED: PunishResult = PunishResult(Outcome.CANCELLED, null, "Another plugin stopped that.")
    }
}
