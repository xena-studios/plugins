package co.xenastudios.xvote

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.text.Component

/**
 * What xVote needs from the server it runs on, written by each adapter (Bukkit, Fabric, Velocity,
 * BungeeCord). Every method is safe from any thread unless it says otherwise; the adapter moves work
 * to the thread it belongs on.
 */
public interface VoteServer {
    /** Whether this is a proxy (Velocity, BungeeCord): it receives and counts votes, and the servers pay them. */
    public val proxy: Boolean get() = false

    /** Every online player: on a proxy, everyone on the network. */
    public fun online(): List<PlayerRef>

    /** An online player's name, or `null`. */
    public fun name(player: PlayerRef): String?

    /** Sends [message] to an online player. */
    public fun send(player: PlayerRef, message: Component)

    /** Writes [message] to the console. */
    public fun console(message: Component)

    /** Whether online [player] has [node], as the server's permissions say. */
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /** Runs [line] (no slash) as the console, on the server's global thread: a reward's command. */
    public fun dispatch(line: String)

    /** The name the server knows [player] by, even offline (Bukkit's user cache); `null` if it doesn't. Off the server's threads. */
    public fun nameOf(player: PlayerRef): String? = null

    /** A player the server knows by [name] (its user cache), from memory, with their name as it has it; `null` if it doesn't. */
    public fun known(name: String): Pair<PlayerRef, String>? = null

    /**
     * Reads an item as the config writes it, like `/give` (`diamond 3`, `diamond_sword[...] 1`):
     * something [give] takes, or why it isn't one. At load, on any thread.
     */
    public fun item(written: String): Outcome<Any, String> = Outcome.Err("items aren't given here")

    /**
     * Gives online [player] [items] (each what [item] returned), on their thread; what didn't fit
     * comes back as bytes for `/vote claim` ([handOut]), with how many items that is. On a server
     * that drops what doesn't fit (Fabric), nothing comes back.
     */
    public fun give(player: PlayerRef, items: List<Any>): Pair<List<ByteArray>, Int> = emptyList<ByteArray>() to 0

    /**
     * Hands online [player] items [give] couldn't, on their thread: how many were given, and what
     * still didn't fit (as bytes, with its count). An item that can't be read any more is dropped
     * with a warning.
     */
    public fun handOut(player: PlayerRef, held: List<ByteArray>): Triple<Int, List<ByteArray>, Int> = Triple(0, held, 0)

    /** Plays [sound] to online [player]. */
    public fun playSound(player: PlayerRef, sound: Sound) {}

    /** Whether the server knows [sound], or `null` if it can't tell. */
    public fun knowsSound(sound: Sound): Boolean? = null
}

/** Money for rewards: xMoney's API (the family), Vault's economy on Bukkit, or none. */
public interface Economy {
    /** What it is, for the console: `xMoney`, `Vault (EssentialsX Economy)`. */
    public val name: String

    /** Whether there's an economy to pay through now. */
    public val ready: Boolean

    /** Pays [player] [amount], recorded as a vote reward: `null` once paid, or why it wasn't. On the player's thread. */
    public fun give(player: PlayerRef, amount: Double): Pending<String?>
}

/** The Votifier receiver's state, for `/xvote status`. */
public class ReceiverStatus(public val port: Int, public val listening: Boolean, public val v1: Boolean, public val tokens: Int)

/** What the receiver is asked to be: the config's `receiver` section, or `null` for off. */
public class ReceiverSettings(
    public val host: String,
    public val port: Int,
    public val v1: Boolean,
    public val maxConnections: Int,
    public val perIpConnections: Int,
    public val timeoutSeconds: Int,
    public val perIpPerMinute: Int,
) {
    override fun equals(other: Any?): Boolean = other is ReceiverSettings && other.host == host && other.port == port && other.v1 == v1 &&
        other.maxConnections == maxConnections && other.perIpConnections == perIpConnections && other.timeoutSeconds == timeoutSeconds &&
        other.perIpPerMinute == perIpPerMinute

    override fun hashCode(): Int = (host.hashCode() * 31 + port) * 31 + maxConnections

    override fun toString(): String = "ReceiverSettings($host:$port)"
}

/**
 * The Votifier receiver, on the JVM (`ReceiverHost`): its own port, keys in `rsa/` and tokens in
 * `tokens.yml`, as NuVotifier keeps them. Every call that reads or writes files, or binds, is made
 * off the server's threads.
 */
public interface VoteReceiver {
    /** Makes the receiver match [settings]: started, restarted, or stopped for `null`. Blocks. */
    public fun apply(settings: ReceiverSettings?)

    /** Stops listening for good, as the plugin is disabled. */
    public fun close()

    /** Its state now, or `null` when it isn't running. */
    public val status: ReceiverStatus?

    /** Where the public key is, as an owner finds it: `plugins/xVote/rsa/public.key`. */
    public val keyFile: String

    /** The public key lists ask for (Base64), or `null` if there's none. */
    public fun publicKey(): String?

    /** The tokens by service name, or `null` if they couldn't be read. */
    public fun tokens(): Map<String, String>?

    /** A new token, as NuVotifier makes them. */
    public fun newToken(): String

    /** Saves [token] for [service] and uses it from now on: `null`, or why it couldn't. Blocks. */
    public fun saveToken(service: String, token: String): String?

    /** Sends a signed v2 test vote to its own port: `null` once accepted, or what went wrong. Blocks. */
    public fun sendTest(service: String, username: String, timestamp: String): TestSend?
}

/** What a test vote sent through the receiver's port met. */
public class TestSend(
    /** Whether the receiver couldn't be reached at all, rather than refusing the vote. */
    public val unreachable: Boolean,
    public val reason: String,
)

/**
 * What `/xvote import` reads, on the JVM (`FileImports`): NuVotifier's keys, tokens and port, and
 * VotingPlugin's and SuperbVote's totals. Off the server's threads.
 */
public interface VoteImports {
    /** NuVotifier's setup taken over: what was taken, in English, or why not; `null` if there's no NuVotifier folder. */
    public fun nuVotifier(): Outcome<String, String>?

    /** [source]'s totals (`votingplugin` or `superbvote`): where they were read from, and each player's; or why not. */
    public fun totals(source: String): Outcome<Pair<String, List<Imported>>, String>
}

/** One player's total from another plugin. */
public class Imported(public val player: PlayerRef, public val name: String, public val total: Int, public val lastVote: Long)
