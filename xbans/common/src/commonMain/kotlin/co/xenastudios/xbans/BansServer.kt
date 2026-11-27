package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.text.Component

/**
 * What xBans needs from the server it runs on, written by each adapter (Bukkit, Fabric, Velocity,
 * BungeeCord), as xTeleport's `TeleportServer`. Every method is safe from any thread unless it
 * says otherwise; the adapter moves work to the thread it belongs on (a player's own on Folia).
 */
public interface BansServer {
    /** Whether this is a proxy (Velocity, BungeeCord): it turns players away at the network's door and kicks them off the network. */
    public val proxy: Boolean get() = false

    /** Every online player. */
    public fun online(): List<PlayerRef>

    public fun isOnline(player: PlayerRef): Boolean

    /** An online player's name, or `null`. */
    public fun name(player: PlayerRef): String?

    /** Where an online player connected from, as written (`203.0.113.9`), or `null` if it isn't known. */
    public fun address(player: PlayerRef): String?

    /** Sends [message] to an online player. */
    public fun send(player: PlayerRef, message: Component)

    /** Writes [message] to the console. */
    public fun console(message: Component)

    /**
     * Disconnects an online player with [screen]: from the server, or from the whole network on a
     * proxy. With [quiet], their "left the game" line is left out. On the player's thread.
     */
    public fun kick(player: PlayerRef, screen: Component, quiet: Boolean)

    /** Whether online [player] has [node], as the server's permissions say. */
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /**
     * The highest `xbans.weight.<n>` online [player] has been given, 0 without one. Bukkit reads
     * the effective permissions; platforms whose permissions answer one node at a time ask for
     * 1 to 100 and the round numbers to 1,000.
     */
    public fun weight(player: PlayerRef): Int

    /** Runs [line] (no slash) as the console, on the server's global thread: a warning's action. */
    public fun dispatch(line: String)

    /**
     * Every name a command typed as [label] answers to (its name, its label and its aliases), for
     * mutes that block commands by what they are, not by how they're typed; `null` if no command
     * has that label. On the thread the command was typed on.
     */
    public fun commandNames(label: String): List<String>?

    /**
     * A player who has never joined, by [name], as the server finds them: its own cache, Mojang's
     * profiles, or on an offline-mode server the name's offline uuid. With their name as found.
     * Off the server's threads; `null` if there's no such player. 1.x looked names up this way.
     */
    public fun lookup(name: String): Pending<Pair<PlayerRef, String>?> = pendingOf(null)

    /** The server's offset from UTC at [epochMillis], in seconds, for dates in its time zone. */
    public fun utcOffsetSeconds(epochMillis: Long): Int
}
