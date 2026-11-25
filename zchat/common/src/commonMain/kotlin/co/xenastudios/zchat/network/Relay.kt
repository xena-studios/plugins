package co.xenastudios.zchat.network

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.proxy.ProxyJoinEvent
import co.xenastudios.core.common.proxy.ProxyQuitEvent
import co.xenastudios.core.common.proxy.Sent
import co.xenastudios.core.common.proxy.ServerSwitchEvent
import co.xenastudios.zchat.concurrentMap

/**
 * zChat on the proxy: the relay between its servers. Servers can only talk to the proxy, so a chat
 * line one server sends comes here and goes on to every server with a player on it; a private
 * message to a player on another server is routed to theirs, and the answer back. It keeps who's on
 * the network and where (the roster), from the proxy's own joins, switches and quits, and tells
 * every server, so `/msg` completes and finds players anywhere.
 *
 * It decides nothing about chat: each server checks its own players' messages (the filter, the
 * mute, other plugins' mute providers) before they leave it, and draws them in its own format, so
 * the proxy carries finished lines. Nothing is queued: a server with nobody on it needs no chat,
 * and a message that can't go ([Sent.NO_PLAYER]) is dropped, as the proxy layer does.
 */
public class Relay(private val channel: ProxyChannel, events: EventBus, private val log: Log) {
    private val roster = concurrentMap<PlayerRef, Wire.Player>()

    /** How many players are on the network, for `/zchatproxy`. */
    public val players: Int get() = roster.size

    init {
        events.subscribe(ProxyJoinEvent.TYPE) { roster[it.player] = Wire.Player(it.player, it.name, "") }
        events.subscribe(ServerSwitchEvent.TYPE) { switch ->
            val name = roster[switch.player]?.name ?: return@subscribe
            val moved = Wire.Player(switch.player, name, switch.to)
            roster[switch.player] = moved
            channel.send(Peer.AllServers, Wire.JOINED, Wire.joined(moved))
        }
        events.subscribe(ProxyQuitEvent.TYPE) { quit ->
            if (roster.remove(quit.player) != null) channel.send(Peer.AllServers, Wire.LEFT, Wire.left(quit.player))
        }
        channel.listen(Wire.HELLO) { message ->
            val from = message.from as? Peer.Server ?: return@listen
            if (Wire.readHello(message.payload) == null) return@listen dropped(Wire.HELLO)
            val everyone = roster.values.filter { it.server.isNotEmpty() }
            channel.send(from, Wire.YOU, Wire.you(from.name, everyone))
        }
        channel.listen(Wire.CHAT) { message ->
            val from = message.from as? Peer.Server ?: return@listen
            val chat = Wire.readChat(message.payload) ?: return@listen dropped(Wire.CHAT)
            // Where it came from is the proxy's to say, never the sender's.
            channel.send(Peer.AllServers, Wire.CHAT, Wire.chat(Wire.Chat(from.name, chat.sender, chat.senderName, chat.line, chat.plain, chat.pinged, chat.everyone)))
        }
        channel.listen(Wire.PM) { message ->
            val from = message.from as? Peer.Server ?: return@listen
            val pm = Wire.readPm(message.payload) ?: return@listen dropped(Wire.PM)
            val target = roster.values.firstOrNull { it.name.equals(pm.toName, ignoreCase = true) && it.server.isNotEmpty() }
            val sent = if (target == null) Sent.UNKNOWN_SERVER else {
                channel.send(Peer.Server(target.server), Wire.PM, Wire.pm(Wire.Pm(from.name, pm.id, pm.from, pm.fromName, target.name, pm.message, pm.plain, pm.reply)))
            }
            if (sent != Sent.SENT) channel.send(from, Wire.PM_DONE, Wire.pmDone(Wire.PmDone(from.name, pm.id, pm.from, null, pm.toName, delivered = false)))
        }
        channel.listen(Wire.PM_DONE) { message ->
            val done = Wire.readPmDone(message.payload) ?: return@listen dropped(Wire.PM_DONE)
            channel.send(Peer.Server(done.origin), Wire.PM_DONE, message.payload)
        }
    }

    private fun dropped(topic: String) {
        log.warn("A $topic message on ${channel.name} in a layout this zChat doesn't read was dropped: is every server on the same zChat?")
    }
}
