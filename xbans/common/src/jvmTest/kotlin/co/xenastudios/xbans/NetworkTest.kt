package co.xenastudios.xbans

import co.xenastudios.core.testing.FakeNetwork
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A network on one database (decisions/2026-11-26-xbans-2.md): two servers, a and b, and the proxy,
 * each an xBans on its own fake platform, sharing one SQLite file through the change feed as a
 * network shares MySQL. A ban made on a takes the player off b and turns them away at the proxy's
 * door; a mute made on a stops their chat on b; one made on the proxy reaches the servers. And the
 * proxy layer's fast path: a ban made on a server with a player on it reaches the proxy at once.
 */
class NetworkTest {
    private val folder = Files.createTempDirectory("xbans-network")
    private fun config(name: String) = "network:\n  enabled: true\n  check-every: 1\nscopes:\n  server-name: $name\n"
    private val wire = FakeNetwork()
    private val a = Harness(folder, FakeBansServer(), config("a"), network = true, channel = { wire.serverChannel("a", it, "xbans") }).start()
    private val b = Harness(folder, FakeBansServer(), config("b"), network = true, channel = { wire.serverChannel("b", it, "xbans") }).start()
    private val proxy = Harness(folder, FakeBansServer(proxy = true), config("proxy"), network = true, channel = { wire.proxyChannel(it, "xbans") }).start()
    private val friend = FakeBansServer.ref("Friend")

    init {
        for (h in listOf(a, b, proxy)) h.server.known["friend"] = friend
    }

    @AfterTest
    fun stop() {
        for (h in listOf(a, b, proxy)) h.stop()
        folder.toFile().deleteRecursively()
    }

    // Each server's feed checks every second of its own virtual time.
    private fun everyoneUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            for (h in listOf(a, b, proxy)) h.platform.advance(1.seconds)
            check(System.nanoTime() < deadline) { "$what didn't happen within 15 seconds" }
            Thread.sleep(5)
        }
    }

    @Test
    fun aBanOnOneServerReachesTheOtherAndTheProxysDoor() {
        b.server.join("Friend")
        proxy.server.join("Friend")
        proxy.plugin.switched(friend, "b")
        a.run("ban Friend cheating")
        everyoneUntil("Friend off b and the network") { b.server.kicked.isNotEmpty() && proxy.server.kicked.isNotEmpty() }
        assertTrue(b.server.kicked.single().second.contains("banned"))
        assertTrue(proxy.server.kicked.single().second.contains("banned"))
        assertNotNull(proxy.plugin.banOnJoin(friend, "203.0.113.9", null), "the proxy turns them away at its door")
        // The proxy hears of it from the feed only, so it isn't announced to its players, only its console.
        proxy.platform.until("the proxy's console line") { proxy.server.console.any { "banned Friend" in it } }
        a.run("unban Friend")
        everyoneUntil("the unban everywhere") { proxy.plugin.banOnJoin(friend, null, null) == null && b.plugin.banOnJoin(friend, null) == null }
    }

    @Test
    fun aMuteOnOneServerStopsChatOnAnother() {
        b.server.join("Friend")
        a.run("mute Friend 1h spam")
        // b holds the mute before it tells them (on the database's thread): wait for what they're told.
        val received = b.server.players.getValue(friend).received
        everyoneUntil("b telling them, as they're on b") { synchronized(received) { received.any { "You were muted" in it } } }
        assertNotNull(b.plugin.muteOf(friend), "the mute on b")
        assertTrue(b.plugin.mutedMessage(friend, b.plugin.muteOf(friend)!!)!!.plain().contains("You're muted for"))
    }

    @Test
    fun aBanMadeOnTheProxyReachesTheServers() {
        proxy.run("ban Friend from the proxy")
        everyoneUntil("the proxy's ban on a and b") { a.plugin.banOnJoin(friend, null) != null && b.plugin.banOnJoin(friend, null) != null }
    }

    @Test
    fun aBanScopedToOneServerKeepsThemOffThatServerOnly() {
        a.run("ban Friend server:b griefing on b")
        // The proxy and b each read it from the database in their own time: wait for both.
        everyoneUntil("the scoped ban at the proxy and on b") {
            proxy.plugin.backend!!.active.size > 0 && b.plugin.banOnJoin(friend, null) != null
        }
        assertNull(proxy.plugin.banOnJoin(friend, null, null), "the network's door lets them in")
        assertNotNull(proxy.plugin.banOnJoin(friend, null, "b"), "but not onto b")
        assertNull(proxy.plugin.banOnJoin(friend, null, "a"))
        assertNull(a.plugin.banOnJoin(friend, null))
        assertNotNull(b.plugin.banOnJoin(friend, null))
    }

    @Test
    fun theProxyLayerBringsABanToTheProxyAtOnce() {
        // A player on a carries the message; Friend is on b, through the proxy.
        a.server.join("Carrier")
        wire.join("a", FakeBansServer.ref("Carrier"))
        proxy.server.join("Friend")
        proxy.plugin.switched(friend, "b")
        a.run("ban Friend fast")
        // No feed check here: only what's due now runs on the proxy.
        proxy.platform.until("the proxy's kick") { proxy.server.kicked.isNotEmpty() }
        assertEquals("Friend", proxy.server.kicked.single().first)
    }
}
