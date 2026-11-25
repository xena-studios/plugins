package co.xenastudios.zchat

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.proxy.ProxyJoinEvent
import co.xenastudios.core.common.proxy.ProxyQuitEvent
import co.xenastudios.core.common.proxy.ServerSwitchEvent
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeChat
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakeNetwork
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.zchat.config.ZChatConfig
import co.xenastudios.zchat.network.Relay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Two servers behind a proxy, each with zChat, and zChat's relay on the proxy: chat and /msg across them. */
class NetworkTest {
    private val network = FakeNetwork()
    private val proxyPlatform = FakePlatform()
    private val proxyEvents = SimpleEventBus(proxyPlatform.log)
    private val relay = Relay(network.proxyChannel(proxyPlatform, "zchat"), proxyEvents, proxyPlatform.log)

    private val config = (Configs.load(ZChatConfig, "network:\n  enabled: true\n", TestYaml) as Outcome.Ok).value.config

    /** One backend: its platform, players and zChat. */
    inner class Backend(val name: String) {
        val platform = FakePlatform()
        val server = FakeServer()
        private var chat = FakeChat(platform.log)
        private val joined = LinkedHashMap<co.xenastudios.core.api.PlayerRef, String>()
        private val loaded = Loaded(config, Translations.of(ZChatMessages.catalog, emptyMap()))
        val plugin = ZChatPlugin(
            platform, server,
            object : Files {
                override fun load() = loaded
                override fun editWords(word: String, add: Boolean) = Outcome.Ok(false)
            },
            server,
            { FakeChat(platform.log).also { fresh -> chat = fresh; joined.forEach { (ref, n) -> fresh.join(ref, n) } } },
        ).also { plugin ->
            plugin.connect(network.serverChannel(name, platform, "zchat"))
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
        }

        fun join(source: FakeCommandSource): FakeServer.Player {
            val player = server.add(source)
            platform.scheduler.spawn(player.ref.uuid, Location(WorldRef.of("world"), 0.0, 64.0, 0.0))
            joined[player.ref] = source.name
            chat.join(player.ref, source.name)
            network.join(name, player.ref)
            proxyEvents.fire(ProxyJoinEvent.TYPE, ProxyJoinEvent(player.ref, source.name))
            proxyEvents.fire(ServerSwitchEvent.TYPE, ServerSwitchEvent(player.ref, null, name))
            plugin.joined(player.ref, source.name)
            settle()
            return player
        }

        fun run(source: FakeCommandSource, line: String) {
            val result = platform.scheduler.runAs(Where.Global) { plugin.dispatcher.dispatch(source, line) }
            plugin.texts.feedback.message(result, source)?.let(source::reply)
            settle()
        }

        fun say(player: FakeServer.Player, text: String) = chat.say(player.ref, text).also { settle() }
    }

    private val lobby = Backend("lobby")
    private val game = Backend("game")

    // Lets every platform's tasks run, a few times over: a message hops from server to proxy to server.
    private fun settle() {
        repeat(4) {
            for (p in listOf(proxyPlatform, lobby.platform, game.platform)) {
                p.scheduler.advance(1.seconds)
                p.scheduler.tick()
            }
        }
    }

    private val steve = FakeCommandSource.player("Steve", "zchat.command.msg", "zchat.command.reply")
    private val alex = FakeCommandSource.player("Alex", "zchat.command.msg", "zchat.command.reply", "zchat.command.togglechat")
    private val stevePlayer = lobby.join(steve)
    private val alexPlayer = game.join(alex)

    @AfterTest
    fun stop() {
        for (backend in listOf(lobby, game)) backend.platform.scheduler.runAs(Where.Global) { backend.plugin.stop() }
    }

    @Test
    fun theProxyNamesEachServerAndKnowsWhosWhere() {
        assertEquals("lobby", lobby.plugin.network?.server)
        assertEquals("game", game.plugin.network?.server)
        assertEquals(2, relay.players)
        assertTrue("Alex" in lobby.plugin.messages.names(steve), lobby.plugin.messages.names(steve).toString())
    }

    @Test
    fun chatCrossesServersInTheSendersFormat() {
        steve.permissions += "zchat.group.vip"
        val said = lobby.say(stevePlayer, "hello game, hi alex")
        assertEquals("[VIP] Steve » hello game, hi @Alex", said.seenBy(stevePlayer.ref))
        assertEquals("[lobby] [VIP] Steve » hello game, hi @Alex", alexPlayer.messages.last().plain())
        assertEquals(1, alexPlayer.sounds.size, "a mention from another server pings")
        assertTrue(game.server.console.any { it.plain() == "[lobby] [VIP] Steve » hello game, hi @Alex" })
        // The filter is the sender's server's, before the line leaves it.
        lobby.platform.scheduler.advance(5.seconds)
        lobby.say(stevePlayer, "what a badword1")
        assertEquals("[lobby] Steve: what a ********", alexPlayer.messages.last().plain().replace("[VIP] Steve »", "Steve:"))
        // Hidden chat is the receiving server's.
        game.run(alex, "togglechat")
        val before = alexPlayer.messages.size
        lobby.platform.scheduler.advance(5.seconds)
        lobby.say(stevePlayer, "anyone?")
        assertEquals(before, alexPlayer.messages.size)
    }

    @Test
    fun privateMessagesCrossServersAndCanBeAnswered() {
        lobby.run(steve, "msg Alex psst over here")
        assertEquals("Steve → You: psst over here", alexPlayer.messages.last().plain())
        assertEquals("You → Alex: psst over here", steve.replies.last())
        assertEquals(1, alexPlayer.sounds.size)
        game.run(alex, "r got it")
        assertEquals("Alex → You: got it", stevePlayer.messages.last().plain())
        assertEquals("You → Steve: got it", alex.replies.last())
        lobby.run(steve, "msg Nobody hi")
        assertEquals("» No player called Nobody is online.", steve.replies.last())
        // Alex leaves the network: the proxy can't find them.
        network.leave(alexPlayer.ref)
        proxyEvents.fire(ProxyQuitEvent.TYPE, ProxyQuitEvent(alexPlayer.ref, "Alex"))
        settle()
        assertNull(lobby.plugin.messages.names(steve).firstOrNull { it == "Alex" })
    }
}
