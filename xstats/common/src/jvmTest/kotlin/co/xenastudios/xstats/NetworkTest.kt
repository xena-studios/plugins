package co.xenastudios.xstats

import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.api.StatUnit
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Network mode, on one SQLite file two servers and the proxy share (standing in for a network's
 * MySQL, as xVote's network test does): each server counts its own players, every server's
 * leaderboards add the network up once it hears the other saved, and the proxy reads it all
 * (decisions/2026-11-28-xstats-2.md).
 */
class NetworkTest {
    private val folder = Files.createTempDirectory("xstats-network")
    private val running = ArrayList<Harness>()

    private fun server(name: String): Harness =
        Harness(folder, config = "server-name: $name\nnetwork:\n  enabled: true\n  check-every: 1\n", network = true).start().also { running += it }

    private fun proxy(): Harness =
        Harness(folder, proxy = true, config = "network:\n  enabled: true\n  check-every: 1\nproxy:\n  refresh-seconds: 5\n", network = true).start().also { running += it }

    @AfterTest
    fun stop() {
        running.forEach { runCatching { it.stop() } }
    }

    // The other side hears of a save through the change feed, checked every second.
    private fun Harness.hearsOf(other: () -> Boolean) {
        platform.until("the network's news") {
            platform.advance(1.seconds)
            Thread.sleep(20)
            other()
        }
    }

    @Test
    fun aStatCountedOnOneServerIsOnTheNetworksBoards() {
        val lobby = server("lobby")
        val game = server("game")
        val proxy = proxy()
        val steve = lobby.join("Steve")
        steve.counters["player_kills"] = 4
        lobby.save()
        steve.counters["player_kills"] = 9
        lobby.save()
        val alex = game.join("Alex")
        alex.counters["player_kills"] = 6
        game.save()
        // game read the network's numbers at its save once it heard lobby saved; lobby does at its next.
        lobby.hearsOf { lobby.plugin.engine.dirty }
        lobby.save()
        assertEquals(listOf("Steve" to 9L, "Alex" to 6L), lobby.plugin.top("kills", Period.ALL, 5).map { it.name to it.value })
        // The proxy counts nothing; it reads every server's rows once one saves.
        proxy.hearsOf { proxy.plugin.engine.dirty }
        assertTrue(proxy.save())
        assertEquals(listOf("Steve" to 9L, "Alex" to 6L), proxy.plugin.top("kills", Period.ALL, 5).map { it.name to it.value })
        assertEquals(5, proxy.plugin.value(FakeStatsServer.ref("Steve"), "kills", Period.DAILY))
        // A player on two servers is both servers' added up, and a balance is the latest either set.
        lobby.join("Alex").counters["player_kills"] = 2
        lobby.save()
        proxy.hearsOf { proxy.plugin.engine.dirty }
        proxy.save()
        assertEquals(8, proxy.plugin.value(FakeStatsServer.ref("Alex"), "kills", Period.ALL))
        // The proxy's own command, a network leaderboard, from memory.
        proxy.run("networkleaderboard kills")
        assertTrue(proxy.console.replies.any { "Steve" in it && "9" in it }, proxy.console.replies.toString())
        proxy.run("networkstats Alex") { proxy.console.replies.any { "Player kills" in it } }
        assertTrue(proxy.console.replies.any { "Alex's stats" in it })
    }

    @Test
    fun aGaugeIsTheLatestAnyServerSet() {
        val lobby = server("lobby")
        val game = server("game")
        val alex = FakeStatsServer.ref("Alex")
        lobby.join("Alex")
        game.join("Alex")
        lobby.save()
        game.save()
        val onLobby = lobby.plugin.registerStat("xMoney", "balance", "Balance", StatUnit.COUNT, true, 2)
        val onGame = game.plugin.registerStat("xMoney", "balance", "Balance", StatUnit.COUNT, true, 2)
        lobby.settle()
        game.settle()
        onLobby.set(alex, 1000)
        lobby.save()
        game.platform.advance(2.seconds)
        onGame.set(alex, 400)
        game.save()
        lobby.hearsOf { lobby.plugin.engine.dirty }
        lobby.save()
        assertEquals(400, lobby.plugin.value(alex, "xmoney_balance", Period.ALL))
    }

    @Test
    fun privateOnOneServerReachesTheOthers() {
        val lobby = server("lobby")
        val game = server("game")
        lobby.join("Ann").counters["player_kills"] = 3
        lobby.save()
        game.hearsOf { game.plugin.engine.dirty }
        game.save()
        val ann = lobby.server.source("Ann")
        lobby.run("stats private", ann)
        game.hearsOf { game.plugin.isPrivate(FakeStatsServer.ref("Ann")) }
    }
}
