package co.xenastudios.zchat.network

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.proxy.ProxyPayload
import co.xenastudios.core.common.text.Component

/**
 * zChat's messages on `xena:zchat`, between a server and the proxy (DECISIONS.md, "zChat's network
 * chat"). Each payload starts with [VERSION], so a later zChat can change the layout and an older
 * one says it can't read it instead of misreading it.
 */
internal object Wire {
    const val VERSION = 1

    /** A server to the proxy, as a player joins it: who, so the proxy answers with [YOU]. */
    const val HELLO = "hello"

    /** The proxy to a server: the server's name, and who's on the network. */
    const val YOU = "you"

    /** The proxy to every server: a player joined the network or moved to another server. */
    const val JOINED = "joined"

    /** The proxy to every server: a player left the network. */
    const val LEFT = "left"

    /** A chat line: a server to the proxy, then the proxy to every server, with where it came from. */
    const val CHAT = "chat"

    /** A private message to a player on another server: a server to the proxy, the proxy to theirs. */
    const val PM = "pm"

    /** What came of a [PM]: the recipient's server to the proxy, the proxy back to the sender's. */
    const val PM_DONE = "pm-done"

    /** Who's where on the network, as the proxy tells it. */
    class Player(val player: PlayerRef, val name: String, val server: String)

    class Chat(val origin: String, val sender: PlayerRef, val senderName: String, val line: Component, val plain: String, val pinged: List<PlayerRef>, val everyone: Boolean)

    class Pm(val origin: String, val id: Int, val from: PlayerRef, val fromName: String, val toName: String, val message: Component, val plain: String, val reply: Boolean)

    class PmDone(val origin: String, val id: Int, val from: PlayerRef, val to: PlayerRef?, val toName: String, val delivered: Boolean)

    fun hello(player: PlayerRef, name: String): ByteArray = ProxyPayload.write { int(VERSION); player(player); text(name) }

    fun readHello(bytes: ByteArray): Pair<PlayerRef, String>? = read(bytes) { player() to text() }

    fun you(server: String, players: List<Player>): ByteArray = ProxyPayload.write {
        int(VERSION)
        text(server)
        int(players.size)
        for (p in players) {
            player(p.player)
            text(p.name)
            text(p.server)
        }
    }

    fun readYou(bytes: ByteArray): Pair<String, List<Player>>? = read(bytes) {
        val server = text()
        val count = int()
        require(count in 0..MAX_ROSTER) { "a roster of $count" }
        server to List(count) { Player(player(), text(), text()) }
    }

    fun joined(p: Player): ByteArray = ProxyPayload.write { int(VERSION); player(p.player); text(p.name); text(p.server) }

    fun readJoined(bytes: ByteArray): Player? = read(bytes) { Player(player(), text(), text()) }

    fun left(player: PlayerRef): ByteArray = ProxyPayload.write { int(VERSION); player(player) }

    fun readLeft(bytes: ByteArray): PlayerRef? = read(bytes) { player() }

    fun chat(c: Chat): ByteArray = ProxyPayload.write {
        int(VERSION)
        text(c.origin)
        player(c.sender)
        text(c.senderName)
        Lines.write(this, c.line)
        text(c.plain)
        boolean(c.everyone)
        int(c.pinged.size)
        for (p in c.pinged) player(p)
    }

    fun readChat(bytes: ByteArray): Chat? = read(bytes) {
        val origin = text()
        val sender = player()
        val name = text()
        val line = Lines.read(this)
        val plain = text()
        val everyone = boolean()
        val count = int()
        require(count in 0..MAX_ROSTER) { "$count mentioned" }
        Chat(origin, sender, name, line, plain, List(count) { player() }, everyone)
    }

    fun pm(p: Pm): ByteArray = ProxyPayload.write {
        int(VERSION)
        text(p.origin)
        int(p.id)
        player(p.from)
        text(p.fromName)
        text(p.toName)
        Lines.write(this, p.message)
        text(p.plain)
        boolean(p.reply)
    }

    fun readPm(bytes: ByteArray): Pm? = read(bytes) { Pm(text(), int(), player(), text(), text(), Lines.read(this), text(), boolean()) }

    fun pmDone(d: PmDone): ByteArray = ProxyPayload.write {
        int(VERSION)
        text(d.origin)
        int(d.id)
        player(d.from)
        boolean(d.to != null)
        d.to?.let { player(it) }
        text(d.toName)
        boolean(d.delivered)
    }

    fun readPmDone(bytes: ByteArray): PmDone? = read(bytes) {
        val origin = text()
        val id = int()
        val from = player()
        val to = if (boolean()) player() else null
        PmDone(origin, id, from, to, text(), boolean())
    }

    private const val MAX_ROSTER = 5000

    // A payload in another layout, or one that doesn't read, is null: the caller drops it.
    private fun <T> read(bytes: ByteArray, block: ProxyPayload.Reader.() -> T): T? = try {
        ProxyPayload.read(bytes) { if (int() != VERSION) null else block() }
    } catch (e: IllegalArgumentException) {
        null
    }
}
