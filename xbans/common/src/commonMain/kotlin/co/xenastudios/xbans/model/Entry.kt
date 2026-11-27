package co.xenastudios.xbans.model

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.Removal

/**
 * A punishment as xBans keeps it: the API's, plus the IP key it was made on (the address, or its
 * salted hash), which the API never shows, and the weight of whoever made it, which decides who
 * may lift it ([CONSOLE] for the console, plugins and imports, 0 for staff without one).
 */
public class Entry(
    public val punishment: Punishment,
    /** The IP key, or `null` when it isn't known or the punishment isn't by IP. */
    public val ip: String?,
    public val staffWeight: Int = 0,
) {
    public val id: Long get() = punishment.id
    public val type: PunishmentType get() = punishment.type
    public val target: PlayerRef? get() = punishment.target

    /** In force at [now] (epoch milliseconds), whatever its scope. */
    public fun active(now: Long): Boolean {
        val p = punishment
        val expires = p.expires
        return p.type.lasts && p.removal == null && (expires == null || expires.toEpochMilliseconds() > now)
    }

    /** Whether it applies on the server called [server]. */
    public fun appliesOn(server: String): Boolean = punishment.isGlobal || punishment.scope.equals(server, ignoreCase = true)

    /** The same entry with its database id. */
    public fun withId(id: Long): Entry {
        val p = punishment
        return Entry(
            Punishment(id, p.type, p.target, p.targetName, p.ipBased, p.reason, p.staff, p.staffName, p.created, p.expires, p.scope, p.silent, p.template, p.removal),
            ip, staffWeight,
        )
    }

    /** The same entry, lifted. */
    public fun revoked(removal: Removal): Entry = Entry(punishment.withRemoval(removal), ip, staffWeight)

    /** How long is left at [now], in milliseconds, or -1 when it's permanent. */
    public fun left(now: Long): Long {
        val expires = punishment.expires ?: return -1
        return maxOf(0, expires.toEpochMilliseconds() - now)
    }

    override fun equals(other: Any?): Boolean = other is Entry && other.punishment == punishment && other.ip == ip && other.staffWeight == staffWeight

    override fun hashCode(): Int = punishment.hashCode()

    override fun toString(): String = "Entry($punishment, ip=${ip != null}, weight=$staffWeight)"

    public companion object {
        /** The weight recorded for the console and plugins: higher than any staff member's. */
        public const val CONSOLE: Int = Int.MAX_VALUE
    }
}

/** What a new ban or mute does to one already in force on the same scope. */
public enum class Replacing {
    /** The new one takes its place: the old one is revoked as replaced. */
    REPLACE,

    /** Refused: the old one already reaches everyone the new one would, for at least as long. */
    REFUSE_LONGER,

    /** Refused: as [REFUSE_LONGER], but the old one was given by someone who outranks the sender. */
    REFUSE_OUTRANKED,

    /** Neither covers the other: both stay in force. */
    BOTH,
    ;

    public companion object {
        /**
         * The replacement rule (decisions/2026-11-18-xbans.md): a new one replaces an old one only if
         * it reaches everyone the old one did (an account ban never lifts an IP ban), lasts at least
         * as long (a permanent one as long as a permanent one, so re-banning changes the reason),
         * and the sender could lift the old one themselves. Failing that, if the old one already
         * reaches everyone for at least as long, the new one is refused; if not, both stay.
         *
         * @param newIp the new one's IP key if it's by IP, else `null`
         * @param expires when the new one ends (epoch ms), `null` for never
         */
        public fun of(old: Entry, newAccount: PlayerRef?, newIp: String?, expires: Long?, mayLift: Boolean): Replacing {
            val oldAccount = old.punishment.target
            val oldIp = if (old.punishment.ipBased) old.ip else null
            val oldExpires = old.punishment.expires?.toEpochMilliseconds()
            val replaces = covers(newAccount, newIp, oldAccount, oldIp) && lastsAsLong(expires, oldExpires)
            if (replaces && mayLift) return REPLACE
            if (covers(oldAccount, oldIp, newAccount, newIp) && lastsAsLong(oldExpires, expires)) {
                return if (mayLift) REFUSE_LONGER else REFUSE_OUTRANKED
            }
            return BOTH
        }

        // Whether a punishment on (account a, IP a) reaches everyone one on (b, b) does. A null IP is
        // an account punishment; a null account, an IP's alone.
        private fun covers(accountA: PlayerRef?, ipA: String?, accountB: PlayerRef?, ipB: String?): Boolean =
            (accountB == null || accountB == accountA) && (ipB == null || ipB == ipA)

        private fun lastsAsLong(fresh: Long?, existing: Long?): Boolean = fresh == null || existing != null && fresh >= existing
    }
}
