package co.xenastudios.zchat.network

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.proxy.Sent
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.concurrentMap
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * zChat's end of the network on a server behind Velocity or BungeeCord: sends this server's chat
 * lines and private messages to the proxy ([Relay]) and shows the other servers'. What arrives is
 * handed to [Receiver], which zChat implements: every rule about who sees what (hidden chat,
 * spies, pings) stays the server's, as for its own chat.
 */
public class NetworkChat(private val channel: ProxyChannel, private val platform: Platform, private val receiver: Receiver) {

    /** What a server does with what other servers sent. Called off the server's threads. */
    public interface Receiver {
        /** Another server's chat line, drawn there, from [chat]'s sender. */
        public fun chat(origin: String, sender: PlayerRef, senderName: String, line: Component, plain: String, pinged: List<PlayerRef>, everyone: Boolean)

        /** A private message from a player on another server to one here, by name: `true` if they got it. */
        public fun privateMessage(origin: String, from: PlayerRef, fromName: String, toName: String, message: Component, plain: String, reply: Boolean): PlayerRef?
    }

    /** What became of a private message sent to another server: delivered to [to] (called [toName]), or not. */
    public fun interface Done {
        public fun done(to: PlayerRef?, toName: String, delivered: Boolean)
    }

    /** This server's name on the network, as the proxy calls it: known once a player has joined. */
    @Volatile
    public var server: String? = null
        private set

    private val roster = concurrentMap<PlayerRef, Wire.Player>()
    private val waiting = concurrentMap<Int, Done>()
    private var next = 0
    private val lock = Any()

    init {
        channel.listen(Wire.YOU) { message ->
            val (name, players) = Wire.readYou(message.payload) ?: return@listen
            server = name
            roster.clear()
            for (p in players) roster[p.player] = p
        }
        channel.listen(Wire.JOINED) { message -> Wire.readJoined(message.payload)?.let { roster[it.player] = it } }
        channel.listen(Wire.LEFT) { message -> Wire.readLeft(message.payload)?.let { roster.remove(it) } }
        channel.listen(Wire.CHAT) { message ->
            val chat = Wire.readChat(message.payload) ?: return@listen
            if (chat.origin == server) return@listen
            receiver.chat(chat.origin, chat.sender, chat.senderName, chat.line, chat.plain, chat.pinged, chat.everyone)
        }
        channel.listen(Wire.PM) { message ->
            val pm = Wire.readPm(message.payload) ?: return@listen
            val to = receiver.privateMessage(pm.origin, pm.from, pm.fromName, pm.toName, pm.message, pm.plain, pm.reply)
            channel.send(Peer.Proxy, Wire.PM_DONE, Wire.pmDone(Wire.PmDone(pm.origin, pm.id, pm.from, to, pm.toName, to != null)))
        }
        channel.listen(Wire.PM_DONE) { message ->
            val done = Wire.readPmDone(message.payload) ?: return@listen
            waiting.remove(done.id)?.done(done.to, done.toName, done.delivered)
        }
    }

    /**
     * [player], called [name], joined this server: says hello to the proxy once their connection
     * carries plugin messages (a moment after they join), so it answers with this server's name and
     * the roster.
     */
    public fun joined(player: PlayerRef, name: String) {
        var tries = 0
        platform.scheduler.run(Where.Async, period = 500.milliseconds) { task ->
            val sent = channel.send(Peer.Proxy, Wire.HELLO, Wire.hello(player, name))
            if (sent != Sent.NO_PLAYER || ++tries >= 20) task.cancel()
        }
    }

    /** A player on another server called [name] (any case), and what the server they're on is called. */
    internal fun find(name: String): Wire.Player? = roster.values.firstOrNull { it.name.equals(name, ignoreCase = true) && it.server != server }

    /** Every player on the other servers' names, for completion. */
    public fun names(): List<String> = roster.values.filter { it.server != server }.map { it.name }

    /** Sends this server's chat line to the others. */
    public fun chat(sender: PlayerRef, senderName: String, line: Component, plain: String, pinged: List<PlayerRef>, everyone: Boolean): Sent =
        channel.send(Peer.Proxy, Wire.CHAT, Wire.chat(Wire.Chat(server.orEmpty(), sender, senderName, line, plain, pinged, everyone)))

    /**
     * Sends a private message from [from] to the player called [toName] on another server; [done]
     * hears whether it arrived, or in ten seconds that it didn't.
     */
    public fun privateMessage(from: PlayerRef, fromName: String, toName: String, message: Component, plain: String, reply: Boolean, done: Done) {
        val id = co.xenastudios.core.common.locked(lock) { next++ }
        waiting[id] = done
        val sent = channel.send(Peer.Proxy, Wire.PM, Wire.pm(Wire.Pm(server.orEmpty(), id, from, fromName, toName, message, plain, reply)))
        if (sent != Sent.SENT) {
            waiting.remove(id)?.done(null, toName, false)
            return
        }
        platform.scheduler.run(Where.Async, delay = 10.seconds) { waiting.remove(id)?.done(null, toName, false) }
    }
}
