package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import kotlin.concurrent.Volatile

/**
 * What zChat remembers while the server runs: the mute, hidden chat, spies, each player's last
 * message and reply target, and who's online by name. Kept for the plugin's life, so a reload
 * keeps them. Chat arrives on many threads at once, so everything here is safe from any of them.
 */
public class ChatState {
    /** Whether `/mutechat` muted chat. */
    @Volatile
    public var muted: Boolean = false

    private val hidden = concurrentMap<PlayerRef, Unit>()
    private val spies = concurrentMap<PlayerRef, Unit>()
    private val recent = concurrentMap<PlayerRef, Recent>()
    private val partners = concurrentMap<PlayerRef, Partner>()
    private val names = concurrentMap<String, PlayerRef>()
    private val online = concurrentMap<PlayerRef, String>()

    /** A player's last message that went out: when (in the clock's milliseconds), and its anti-repeat key with when it was said. */
    public class Recent(public val at: Long, public val key: String?, public val keyAt: Long)

    /**
     * Who a player last messaged, or was messaged by: a player here, the console ([player] `null`),
     * or with [remote] a player on another server of the network, by name.
     */
    public class Partner(public val player: PlayerRef?, public val name: String, public val remote: Boolean = false)

    public fun hidesChat(player: PlayerRef): Boolean = player in hidden

    public fun hideChat(player: PlayerRef, hide: Boolean) {
        if (hide) hidden[player] = Unit else hidden.remove(player)
    }

    public fun spies(player: PlayerRef): Boolean = player in spies

    public fun spy(player: PlayerRef, on: Boolean) {
        if (on) spies[player] = Unit else spies.remove(player)
    }

    /** Every player spying now. */
    public fun spies(): Set<PlayerRef> = spies.keys

    public fun recent(player: PlayerRef): Recent? = recent[player]

    /** Notes [player]'s message that went out, unless they've left meanwhile (chat runs off the main thread). */
    public fun recent(player: PlayerRef, value: Recent) {
        recent[player] = value
        if (player !in online) recent.remove(player)
    }

    public fun partner(player: PlayerRef): Partner? = partners[player]

    public fun partner(player: PlayerRef, partner: Partner) {
        partners[player] = partner
        if (player !in online) partners.remove(player)
    }

    /** The console's reply target. */
    @Volatile
    public var consolePartner: Partner? = null

    /** The online player called [name], ignoring case, or `null`. */
    public fun online(name: String): PlayerRef? = names[name.lowercase()]

    /** Whether [player] is online. */
    public fun isOnline(player: PlayerRef): Boolean = player in online

    /** [player]'s name, if they're online. */
    public fun name(player: PlayerRef): String? = online[player]

    /** Every online player. */
    public fun online(): Set<PlayerRef> = online.keys

    /** Every online player's name. */
    public fun names(): Collection<String> = online.values

    /** Notes a player who joined. */
    public fun joined(player: PlayerRef, name: String) {
        online[player] = name
        names[name.lowercase()] = player
    }

    /** Forgets a player who left. Hidden chat and spying are kept in [PlayerSettings], if remembered. */
    public fun quit(player: PlayerRef) {
        online.remove(player)?.let { name -> if (names[name.lowercase()] == player) names.remove(name.lowercase()) }
        hidden.remove(player)
        spies.remove(player)
        recent.remove(player)
        partners.remove(player)
    }
}
