package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.locked
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.model.Length

/**
 * What xBans knows about the players online: the IP key each joined from, worked out once at join
 * so the chat check never hashes anything, and who's being kicked, whose quit line is left out.
 * Safe from any thread.
 */
public class Sessions {
    private val ips = concurrentMap<PlayerRef, String>()
    // How many online players are on each IP key, to notice a proxy that isn't forwarding addresses.
    private val sharing = HashMap<String, Int>()
    private val quiet = concurrentMap<PlayerRef, Boolean>()

    /** The IP key an online player joined from, or `null`. */
    public fun ip(player: PlayerRef): String? = ips[player]

    /** Records the IP key a player joined from; how many online players are on it now. */
    public fun joined(player: PlayerRef, ip: String?): Int = locked(this) {
        if (ip == null) return@locked 0
        val old = ips.put(player, ip)
        if (ip == old) return@locked sharing[ip] ?: 1
        if (old != null) release(old)
        val count = (sharing[ip] ?: 0) + 1
        sharing[ip] = count
        count
    }

    /** Keys every online player's IP again, as a newly opened database keys them. */
    public fun rekey(online: Map<PlayerRef, String>) {
        locked(this) {
            ips.clear()
            sharing.clear()
        }
        for ((player, ip) in online) joined(player, ip)
    }

    public fun left(player: PlayerRef) {
        locked(this) { ips.remove(player)?.let(::release) }
    }

    /** Leaves out the "left the game" line when this player is kicked next. */
    public fun quietQuit(player: PlayerRef) {
        quiet[player] = true
    }

    /** Whether this player's quit line is to be left out, once. */
    public fun takeQuiet(player: PlayerRef): Boolean = quiet.remove(player) != null

    private fun release(ip: String) {
        val count = sharing[ip] ?: return
        if (count <= 1) sharing.remove(ip) else sharing[ip] = count - 1
    }
}

/**
 * What a staff member may do, worked out from their permissions on the thread their command runs
 * on: their weight, the longest ban and mute their groups allow (`null` for no limit), whether a
 * template's own lengths may go past those, and whether they may lift (or replace) anyone's
 * punishment whatever its weight. The console and plugins have no limits at all (`null` power).
 */
public class Power(
    public val weight: Int,
    public val banCap: Length?,
    public val muteCap: Length?,
    public val bypassTemplates: Boolean,
    public val override: Boolean,
) {
    public fun cap(type: PunishmentType): Length? = when (type) {
        PunishmentType.BAN -> banCap
        PunishmentType.MUTE -> muteCap
        else -> null
    }

    public companion object {
        public const val EXEMPT: String = "xbans.exempt"
        public const val BYPASS_TEMPLATES: String = "xbans.limit.bypass-templates"
        public const val OVERRIDE: String = "xbans.unban.override"
        public const val WEIGHT_PREFIX: String = "xbans.weight."

        /** [source]'s power, or `null` for no limits (the console, a command block). */
        public fun of(source: CommandSource, config: Config, server: BansServer): Power? {
            val player = source.player ?: return null
            var ban: Length? = null
            var mute: Length? = null
            var grouped = false
            for (limit in config[XBansConfig.staffLimits.groups].values) {
                if (limit.permission.isNotBlank() && source.hasPermission(limit.permission)) {
                    ban = if (grouped) maxOf(ban!!, limit.ban) else limit.ban
                    mute = if (grouped) maxOf(mute!!, limit.mute) else limit.mute
                    grouped = true
                }
            }
            return Power(
                server.weight(player), ban?.takeIf { !it.permanent }, mute?.takeIf { !it.permanent },
                source.hasPermission(BYPASS_TEMPLATES), source.hasPermission(OVERRIDE),
            )
        }
    }
}

/** An online player as a staff limit sees them: their IP key, weight and exemption. */
public class OnlinePlayer(public val id: PlayerRef, public val name: String, public val ip: String?, public val weight: Int, public val exempt: Boolean)
