package co.xenastudios.core.common.proxy

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.testing.FakeNetwork
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class ProxyChannelTest {
    private val network = FakeNetwork()
    private val proxy = FakePlatform(Threading.PROXY)
    private val lobby = FakePlatform(Threading.MAIN_THREAD)
    private val survival = FakePlatform(Threading.MAIN_THREAD)
    private val proxyEnd = network.proxyChannel(proxy, "xsample")
    private val lobbyEnd = network.serverChannel("lobby", lobby, "xsample")
    private val survivalEnd = network.serverChannel("survival", survival, "xsample")
    private val steve = PlayerRef.of(Uuid.fromLongs(1, 1))
    private val alex = PlayerRef.of(Uuid.fromLongs(2, 2))

    private fun heard(channel: ProxyChannel, topic: String): MutableList<ProxyMessage> =
        mutableListOf<ProxyMessage>().also { list -> channel.listen(topic) { list += it } }

    @Test
    fun aServerReachesTheProxyThroughAPlayerOnIt() {
        val got = heard(proxyEnd, "ping")
        assertEquals(Sent.NO_PLAYER, lobbyEnd.send(Peer.Proxy, "ping", byteArrayOf(1)))
        network.join("lobby", steve)
        assertEquals(Sent.SENT, lobbyEnd.send(Peer.Proxy, "ping", byteArrayOf(1, 2, 3)))
        assertTrue(got.isEmpty(), "listeners run on the proxy's async work, not inside send")
        proxy.scheduler.runPending()
        assertEquals(1, got.size)
        assertEquals(Peer.Server("lobby"), got[0].from)
        assertContentEquals(byteArrayOf(1, 2, 3), got[0].payload)
        assertEquals(lobby.clock.now(), got[0].sentAt)
    }

    @Test
    fun theProxyReachesOneServerOrEveryServerWithAPlayer() {
        val atLobby = heard(lobbyEnd, "chat")
        val atSurvival = heard(survivalEnd, "chat")
        network.join("lobby", steve)
        assertEquals(Sent.SENT, proxyEnd.send(Peer.Server("lobby"), "chat", byteArrayOf(9)))
        assertEquals(Sent.NO_PLAYER, proxyEnd.send(Peer.Server("survival"), "chat", byteArrayOf(9)))
        assertEquals(Sent.UNKNOWN_SERVER, proxyEnd.send(Peer.Server("nether"), "chat", byteArrayOf(9)))
        network.join("survival", alex)
        assertEquals(Sent.SENT, proxyEnd.send(Peer.AllServers, "chat", byteArrayOf(7)))
        lobby.scheduler.runPending()
        survival.scheduler.runPending()
        assertEquals(listOf(9, 7), atLobby.map { it.payload.single().toInt() })
        assertEquals(listOf(7), atSurvival.map { it.payload.single().toInt() })
        assertTrue(atSurvival.all { it.from == Peer.Proxy })
    }

    @Test
    fun eachSideSendsOnlyTheOtherWay() {
        network.join("lobby", steve)
        assertEquals(Sent.WRONG_SIDE, lobbyEnd.send(Peer.Server("survival"), "chat", byteArrayOf()))
        assertEquals(Sent.WRONG_SIDE, lobbyEnd.send(Peer.AllServers, "chat", byteArrayOf()))
        assertEquals(Sent.WRONG_SIDE, proxyEnd.send(Peer.Proxy, "chat", byteArrayOf()))
    }

    @Test
    fun topicsAndPayloadsHaveLimits() {
        network.join("lobby", steve)
        assertEquals(Sent.REFUSED, lobbyEnd.send(Peer.Proxy, "Chat", byteArrayOf()))
        assertEquals(Sent.REFUSED, lobbyEnd.send(Peer.Proxy, "", byteArrayOf()))
        assertEquals(Sent.REFUSED, lobbyEnd.send(Peer.Proxy, "chat", ByteArray(ProxyChannel.MAX_PAYLOAD + 1)))
        assertEquals(Sent.SENT, lobbyEnd.send(Peer.Proxy, "chat", ByteArray(ProxyChannel.MAX_PAYLOAD)))
    }

    @Test
    fun aServerIgnoresWhatAPlayerForgesAndSaysWhy() {
        val got = heard(lobbyEnd, "kick")
        network.join("lobby", steve)
        // Garbage, a frame signed with another secret, and one signed right but claiming to come from a server.
        network.forge("lobby", "xsample", "hello".encodeToByteArray())
        val other = ProxyFrame(true, "kick", lobby.clock.now(), 1, byteArrayOf())
        network.forge("lobby", "xsample", ProxyFrame.encode("xena:xsample", other, FakeNetwork.signer("a guess")))
        val upward = ProxyFrame(false, "kick", lobby.clock.now(), 2, byteArrayOf())
        network.forge("lobby", "xsample", ProxyFrame.encode("xena:xsample", upward, FakeNetwork.signer("the network's secret")))
        lobby.scheduler.runPending()
        assertTrue(got.isEmpty())
        val warnings = lobby.log.warnings
        assertTrue(warnings.any { "isn't a message of ours" in it }, "$warnings")
        assertTrue(warnings.any { "isn't signed with this server's secret" in it }, "$warnings")
        assertTrue(warnings.any { "wrong side" in it }, "$warnings")
    }

    @Test
    fun aMessageForOneChannelIsRefusedOnAnother() {
        val zchat = network.serverChannel("lobby", lobby, "zchat")
        val got = heard(zchat, "kick")
        val frame = ProxyFrame(true, "kick", lobby.clock.now(), 3, byteArrayOf())
        network.forge("lobby", "zchat", ProxyFrame.encode("xena:xsample", frame, FakeNetwork.signer("the network's secret")))
        lobby.scheduler.runPending()
        assertTrue(got.isEmpty())
    }

    @Test
    fun aRepeatOrAnOldMessageIsIgnored() {
        val got = heard(proxyEnd, "vote")
        network.join("lobby", steve)
        lobbyEnd.send(Peer.Proxy, "vote", byteArrayOf(1))
        network.replayToProxy("lobby", "xsample", network.wire.last())
        proxy.scheduler.runPending()
        assertEquals(1, got.size)
        assertTrue(proxy.log.warnings.any { "already received" in it })
        // Signed now, heard six minutes later.
        val stale = ProxyFrame(false, "vote", proxy.clock.now(), 4, byteArrayOf())
        proxy.scheduler.advance(6.minutes)
        network.replayToProxy("lobby", "xsample", ProxyFrame.encode("xena:xsample", stale, FakeNetwork.signer("the network's secret")))
        proxy.scheduler.runPending()
        assertEquals(1, got.size)
        assertTrue(proxy.log.warnings.any { "five minutes" in it })
    }

    @Test
    fun aServerWithAnotherSecretHearsNothing() {
        val nether = FakePlatform(Threading.MAIN_THREAD)
        val netherEnd = network.serverChannel("nether", nether, "xsample", secret = "not the same")
        val got = heard(netherEnd, "chat")
        network.join("nether", steve)
        assertEquals(Sent.SENT, proxyEnd.send(Peer.Server("nether"), "chat", byteArrayOf(1)))
        nether.scheduler.runPending()
        assertTrue(got.isEmpty())
        assertTrue(nether.log.warnings.single().contains("isn't signed with this server's secret"))
    }

    @Test
    fun theConsoleIsToldOnceAMinuteForEachReason() {
        repeat(50) { network.forge("lobby", "xsample", byteArrayOf(1, 2, 3)) }
        assertEquals(1, lobby.log.warnings.size)
        lobby.scheduler.advance(2.minutes)
        network.forge("lobby", "xsample", byteArrayOf(1, 2, 3))
        assertEquals(2, lobby.log.warnings.size)
        assertTrue("50 messages since the last line" in lobby.log.warnings.last(), lobby.log.warnings.last())
    }

    @Test
    fun aCancelledListenerAndAClosedChannelHearNothing() {
        val got = mutableListOf<ProxyMessage>()
        val subscription = proxyEnd.listen("chat") { got += it }
        network.join("lobby", steve)
        subscription.cancel()
        lobbyEnd.send(Peer.Proxy, "chat", byteArrayOf())
        proxy.scheduler.runPending()
        assertTrue(got.isEmpty())
        lobbyEnd.close()
        assertEquals(Sent.CLOSED, lobbyEnd.send(Peer.Proxy, "chat", byteArrayOf()))
    }

    @Test
    fun aChannelWithNoSecretSendsNothing() {
        val endpoint = ProxyEndpoint("xena:xsample", ProxySide.SERVER, lobby, null)
        assertEquals(false, endpoint.ready)
        assertEquals(Sent.NO_SECRET, endpoint.send(Peer.Proxy, "chat", byteArrayOf()))
    }

    @Test
    fun framesRoundTripAndRefuseWhatsCutShort() {
        val signer = FakeNetwork.signer("s")
        val frame = ProxyFrame(true, "a.b-c_d", lobby.clock.now(), -5, byteArrayOf(0, -1, 127))
        val bytes = ProxyFrame.encode("xena:x", frame, signer)
        val back = (ProxyFrame.decode("xena:x", bytes, signer) as Outcome.Ok).value
        assertEquals(true, back.fromProxy)
        assertEquals("a.b-c_d", back.topic)
        assertEquals(-5, back.id)
        assertContentEquals(byteArrayOf(0, -1, 127), back.payload)
        for (cut in bytes.indices) {
            assertTrue(ProxyFrame.decode("xena:x", bytes.copyOf(cut), signer) is Outcome.Err, "cut at $cut")
        }
        val newer = bytes.copyOf().also { it[1] = 2 }
        assertEquals(ProxyFrame.Problem.VERSION, (ProxyFrame.decode("xena:x", newer, signer) as Outcome.Err).error)
    }

    @Test
    fun payloadsReadBackWhatWasWritten() {
        val payload = ProxyPayload.write {
            int(1)
            player(steve)
            text("Grüße, <b>Welt</b>")
            long(Long.MIN_VALUE)
            boolean(true)
        }
        val read = ProxyPayload.read(payload) { listOf(int(), player(), text(), long(), boolean()) }
        assertEquals(listOf(1, steve, "Grüße, <b>Welt</b>", Long.MIN_VALUE, true), read)
        assertNull(ProxyPayload.read(payload) { int() }, "bytes left over")
        assertNull(ProxyPayload.read(byteArrayOf(0, 0)) { int() }, "ends early")
    }
}
