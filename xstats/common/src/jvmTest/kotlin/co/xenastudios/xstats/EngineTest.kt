package co.xenastudios.xstats

import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.api.StatUnit
import co.xenastudios.xstats.data.visibleRank
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The save, end to end on SQLite: readings, other plugins' changes, periods, leaderboards and what a restart keeps. */
class EngineTest {
    private val harnesses = ArrayList<Harness>()

    private fun harness(config: String = "", folder: java.nio.file.Path? = null): Harness =
        (if (folder == null) Harness(config = config) else Harness(folder, config = config)).start().also { harnesses += it }

    @AfterTest
    fun stop() {
        harnesses.forEach { runCatching { it.stop() } }
    }

    private fun Harness.value(name: String, stat: String, period: Period = Period.ALL): Long = plugin.value(FakeStatsServer.ref(name), stat, period)

    @Test
    fun firstReadingIsHistoryAndRisesCountToday() {
        val h = harness()
        val steve = h.join("Steve")
        steve.counters["player_kills"] = 40
        assertTrue(h.save())
        // What they had before xStats counted them is all time, not today.
        assertEquals(40, h.value("Steve", "kills"))
        assertEquals(0, h.value("Steve", "kills", Period.DAILY))
        steve.counters["player_kills"] = 45
        assertTrue(h.save())
        assertEquals(45, h.value("Steve", "kills"))
        assertEquals(5, h.value("Steve", "kills", Period.DAILY))
        assertEquals(5, h.value("Steve", "kills", Period.MONTHLY))
        assertEquals(1, h.plugin.rank(FakeStatsServer.ref("Steve"), "kills", Period.ALL))
        assertEquals("Steve", h.plugin.top("kills", Period.ALL, 3).single().name)
    }

    @Test
    fun beforeAnyoneHasPlayedEveryBoardIsThereAndEmpty() {
        val h = harness()
        // Nobody has played, so the open worked out no board; asked for, every stat has an empty one.
        val boards = h.plugin.engine.leaderboards
        assertEquals(0, boards["kills", Period.ALL]?.top(10, false)?.size)
        assertEquals(0, boards["playtime", Period.DAILY]?.top(10, false)?.size)
        assertNull(boards["nothing", Period.ALL])
        // The first player's save works out every board, not only the stats they changed.
        h.join("Steve").counters["player_kills"] = 2
        h.save()
        assertEquals("Steve", h.plugin.top("kills", Period.ALL, 3).single().name)
        assertEquals("Steve", h.plugin.top("sessions", Period.ALL, 3).single().name)
    }

    @Test
    fun theFirstPlayersSaveMakesTheBoardsNobodyAskedFor() {
        val h = harness()
        h.join("Alex").counters["jump"] = 7
        h.save()
        assertEquals(listOf("Alex" to 7L), h.plugin.top("jumps", Period.ALL, 3).map { it.name to it.value })
        assertEquals(1, h.plugin.top("sessions", Period.ALL, 3).size)
    }

    @Test
    fun aSmallDropIsADecreaseAndABigOneARestart() {
        val h = harness()
        val alex = h.join("Alex")
        alex.counters["jump"] = 100
        h.save()
        // Another plugin took a little away: all time follows.
        alex.counters["jump"] = 95
        h.save()
        assertEquals(95, h.value("Alex", "jumps"))
        // Far lower: the stats file was deleted, the counter restarted from 0. Nothing's lost.
        alex.counters["jump"] = 3
        h.save()
        assertEquals(98, h.value("Alex", "jumps"))
    }

    @Test
    fun sessionsAndPlaytimeLeaveOutTheAfk() {
        val h = harness()
        val sam = h.join("Sam")
        h.save()
        assertEquals(1, h.value("Sam", "sessions"))
        h.platform.advance(2.minutes)
        sam.yaw = 90f
        h.save()
        val active = h.value("Sam", "playtime")
        assertTrue(active >= 120, "two active minutes, was $active")
        // The camera didn't move between these two readings: no playtime counted.
        h.platform.advance(2.minutes)
        h.save()
        assertEquals(active, h.value("Sam", "playtime"))
    }

    @Test
    fun pluginsChangesWaitInMemoryAsOneNumber() {
        val h = harness()
        h.join("Kim")
        h.save()
        val votes = h.plugin.registerStat("xVote", "votes", "Votes", StatUnit.COUNT)
        val balance = h.plugin.registerStat("xMoney", "balance", "Balance", StatUnit.COUNT, true, 2)
        h.settle()
        val kim = FakeStatsServer.ref("Kim")
        repeat(1000) { votes.increment(kim, 1) }
        balance.set(kim, 500)
        balance.set(kim, 800)
        // A thousand increments and two sets wait as two entries, not 1002.
        assertTrue(h.plugin.engine.waiting <= 3, "waiting ${h.plugin.engine.waiting}")
        h.save()
        assertEquals(1000, h.value("Kim", "xvote_votes"))
        assertEquals(1000, h.value("Kim", "xvote_votes", Period.DAILY))
        assertEquals(800, h.value("Kim", "xmoney_balance"))
        assertEquals("xVote", h.plugin.stat("xvote_votes")!!.owner)
    }

    @Test
    fun aRestartKeepsEverything() {
        val first = harness()
        val steve = first.join("Steve")
        steve.counters["player_kills"] = 10
        first.save()
        steve.counters["player_kills"] = 12
        first.save()
        first.leave("Steve")
        first.save()
        first.stop()
        harnesses.remove(first)
        val second = harness(folder = first.folder)
        assertEquals(12, second.value("Steve", "kills"))
        assertEquals(2, second.value("Steve", "kills", Period.DAILY))
        assertEquals(1, second.value("Steve", "sessions"))
        // Back online with the same counter: nothing more is counted.
        second.join("Steve").counters["player_kills"] = 12
        second.save()
        assertEquals(12, second.value("Steve", "kills"))
    }

    @Test
    fun excludedBannedAndVanishedPlayersAreLeftOffBoards() {
        val h = harness()
        for ((name, kills) in listOf("Ann" to 5L, "Ben" to 7L, "Cat" to 9L, "Dan" to 11L)) h.join(name).counters["player_kills"] = kills
        h.server.player("Ben").permissions += "xstats.leaderboard.exclude"
        h.server.banned += FakeStatsServer.ref("Cat")
        h.plugin.engine.vanished = { it == FakeStatsServer.ref("Dan") }
        h.save()
        assertEquals(listOf("Ann"), h.plugin.top("kills", Period.ALL, 10).map { it.name })
        assertEquals(0, h.plugin.rank(FakeStatsServer.ref("Dan"), "kills", Period.ALL))
    }

    @Test
    fun commandsAnswerFromMemory() {
        val h = harness()
        val steve = h.join("Steve")
        steve.counters["player_kills"] = 3
        steve.counters["deaths"] = 2
        h.save()
        h.run("leaderboard kills")
        assertTrue(h.console.replies.any { "Steve" in it && "3" in it }, h.console.replies.toString())
        h.run("leaderboard nothing")
        assertTrue(h.console.replies.last().contains("There's no stat called"))
        h.run("lb jumps daily")
        assertTrue(h.console.replies.last().contains("is only counted all time."))
        h.run("stats Steve") { h.console.replies.any { "Player kills" in it } }
        assertTrue(h.console.replies.any { "Steve's stats" in it }, h.console.replies.toString())
        h.run("stats Nobody")
        assertTrue(h.console.replies.last().contains("has never played here."))
        assertEquals("1.50", Placeholders.resolve(h.plugin, FakeStatsServer.ref("Steve"), "kd"))
        assertEquals("Steve", Placeholders.resolve(h.plugin, null, "top_kills_1_name"))
        assertEquals("3", Placeholders.resolve(h.plugin, FakeStatsServer.ref("Steve"), "kills_raw"))
        assertEquals("1", Placeholders.resolve(h.plugin, FakeStatsServer.ref("Steve"), "rank_kills"))
        assertEquals("", Placeholders.resolve(h.plugin, null, "top_kills_2_name"))
        assertNull(Placeholders.resolve(h.plugin, null, "nonsense"))
    }

    @Test
    fun statsPrivateHidesFromTheSiteAtOnce() {
        val h = harness()
        h.join("Ann").counters["player_kills"] = 5
        h.join("Ben").counters["player_kills"] = 3
        h.save()
        val ann = h.server.source("Ann")
        h.run("stats private", ann)
        assertTrue(h.replies(ann).last().contains("hidden"), h.replies(ann).toString())
        assertTrue(h.plugin.isPrivate(FakeStatsServer.ref("Ann")))
        val ben = h.plugin.engine.player(FakeStatsServer.ref("Ben"))!!
        val kills = h.plugin.catalog.snapshot["kills"]!!
        // In game Ben is second; on the site, with Ann private, first.
        assertEquals(2, h.plugin.engine.rank(ben, kills, Period.ALL))
        assertEquals(1, h.plugin.engine.visibleRank(ben, kills, Period.ALL))
        h.run("stats private", ann)
        assertTrue(h.replies(ann).last().contains("again"))
    }

    @Test
    fun aSourceChangeStartsFromTheNewCounters() {
        val h = harness()
        val steve = h.join("Steve")
        steve.counters["mined:*"] = 100
        steve.counters["mined:stone"] = 60
        h.save()
        assertEquals(100, h.value("Steve", "blocks_mined"))
        h.reload("stats:\n  blocks_mined:\n    name: Stone mined\n    source: mined:stone\n    periods: true\n")
        h.save()
        assertEquals(60, h.value("Steve", "blocks_mined"))
        assertEquals(0, h.value("Steve", "blocks_mined", Period.DAILY))
    }

    @Test
    fun boardsWithoutAHostAreRefused() {
        val h = harness()
        h.run("xstats board add kills")
        assertTrue(h.console.replies.last().contains("text displays"), h.console.replies.toString())
        h.platform.advance(1.seconds)
    }
}
