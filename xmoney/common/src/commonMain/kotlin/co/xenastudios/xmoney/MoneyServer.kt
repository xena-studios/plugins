package co.xenastudios.xmoney

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component

/**
 * What xMoney's shared code asks of the server it runs on, which each adapter (Bukkit, Fabric)
 * answers: who's online, who has played here, and telling a player something.
 */
public interface MoneyServer {
    /** Sends [message] to [player], if they're online. Any thread. */
    public fun send(player: PlayerRef, message: Component)

    /** Whether [player] is online here. */
    public fun isOnline(player: PlayerRef): Boolean

    /** The names of the players online, for completion. */
    public fun onlineNames(): List<String>

    /**
     * A player the server knows by [name] (ignoring case) who has played here, offline or not, with
     * their name as the server has it: from its own caches (the family's player cache, the
     * server's user cache), never the web. `null` if it knows nobody by it.
     */
    public fun known(name: String): Pending<KnownPlayer?>

    /** The name the server has cached for [player], or `null`: never the disk or the web. */
    public fun cachedName(player: PlayerRef): String?
}

/** A player the server knows: their uuid and name. */
public class KnownPlayer(public val player: PlayerRef, public val name: String)

/** The importers an adapter offers, for `/xmoney import`: one object, not a lambda an importer. */
public interface BalanceSources {
    /** The ids `/xmoney import` takes, such as `essentials`. */
    public val ids: List<String>

    /** A reader for [id], or `null` if there's none by it. */
    public fun make(id: String): BalanceSource?

    public companion object {
        public val NONE: BalanceSources = object : BalanceSources {
            override val ids: List<String> get() = emptyList()
            override fun make(id: String): BalanceSource? = null
        }
    }
}
