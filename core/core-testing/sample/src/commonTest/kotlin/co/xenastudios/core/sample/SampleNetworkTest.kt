package co.xenastudios.core.sample

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.testing.FakeNetwork
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// xSample's hello and welcome between a server and its proxy, on the fake network: the flow the
// proxy boot tests run on Velocity and BungeeCord, without servers.
class SampleNetworkTest {
    private val network = FakeNetwork()
    private val proxy = FakePlatform(Threading.PROXY)
    private val lobby = FakePlatform(Threading.MAIN_THREAD)
    private val steve = PlayerRef.of(Uuid.fromLongs(1, 2))

    init {
        SampleNetwork.Proxy(network.proxyChannel(proxy, "xsample"), proxy)
    }

    private val server = SampleNetwork.Server(network.serverChannel("lobby", lobby, "xsample"), lobby)

    private fun lines(platform: FakePlatform) = platform.log.lines.map { it.message }

    @Test
    fun aJoinIsWelcomedOnceItsConnectionCarriesMessages() {
        server.joined(steve, "Steve")
        // Not on the network yet as far as the proxy's channel goes: the hello waits.
        lobby.scheduler.advance(1.seconds)
        network.join("lobby", steve)
        lobby.scheduler.advance(1.seconds)
        proxy.scheduler.runPending()
        lobby.scheduler.runPending()
        assertEquals(listOf("NETWORK HELLO from lobby for Steve"), lines(proxy))
        assertEquals(listOf("NETWORK PASS: the proxy welcomed Steve on xena:xsample"), lines(lobby))
        assertEquals(2, network.wire.size, "one hello, one welcome, however many tries")
    }

    @Test
    fun aServerOnItsOwnGivesUpQuietly() {
        server.joined(steve, "Steve")
        lobby.scheduler.advance(30.seconds)
        assertTrue(lobby.log.lines.isEmpty())
        assertEquals(0, lobby.scheduler.pending)
    }
}
