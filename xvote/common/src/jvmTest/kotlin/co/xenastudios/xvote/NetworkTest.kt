package co.xenastudios.xvote

import co.xenastudios.core.testing.FakeNetwork
import co.xenastudios.xvote.api.VoteResult
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A network on one database (decisions/2026-11-27-xvote-2.md): the proxy, which receives the votes,
 * and two servers, a and b, each an xVote on its own fake platform, sharing one SQLite file through
 * the change feed as a network shares MySQL. A vote received on the proxy is paid on the server its
 * player is on, at once (the proxy layer's message) or within check-every (the feed); one for a
 * player who's offline waits in the database and is paid on whichever server they join first; and
 * nothing is ever paid twice, however the servers race.
 */
class NetworkTest {
    private val folder = Files.createTempDirectory("xvote-network")
    private val config = "network:\n  enabled: true\n  check-every: 1\nrewards:\n  vote:\n    items: [\"diamond 1\"]\n    message: \"\"\n    broadcast: \"\"\n"
    private val wire = FakeNetwork()
    private val proxy = Harness(folder, proxy = true, config = config, network = true, channel = { wire.proxyChannel(it, "xvote") }).start()
    private val a = Harness(folder, config = config, network = true, channel = { wire.serverChannel("a", it, "xvote") }).start()
    private val b = Harness(folder, config = config, network = true, channel = { wire.serverChannel("b", it, "xvote") }).start()
    private val all = listOf(proxy, a, b)
    private val steve = FakeVoteServer.ref("Steve")

    @AfterTest
    fun stop() {
        for (h in all) h.stop()
        folder.toFile().deleteRecursively()
    }

    // Each feed checks every second of its own virtual time.
    private fun everyoneUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            for (h in all) h.platform.advance(1.seconds)
            check(System.nanoTime() < deadline) { "$what didn't happen within 15 seconds" }
            Thread.sleep(5)
        }
    }

    private fun diamonds(h: Harness): Int = h.server.players[steve]?.items?.count { it == "diamond 1" } ?: 0

    /** Steve on [server], as the proxy and that server see it: a join through the proxy. */
    private fun onto(server: Harness, name: String) {
        server.join("Steve")
        wire.join(name, steve)
        proxy.join("Steve")
        proxy.plugin.switched(steve, name)
    }

    @Test
    fun aVoteReceivedOnTheProxyIsPaidAtOnceOnThePlayersServer() {
        onto(b, "b")
        assertEquals(VoteResult.COUNTED, proxy.vote("Steve"))
        // No feed check: only what's due now runs on b, so this is the proxy layer's message.
        b.platform.until("Steve paid on b") { diamonds(b) == 1 }
        assertEquals(0, proxy.server.dispatched.size + proxy.server.players.getValue(steve).items.size, "the proxy never pays")
        assertEquals(1, b.plugin.stats(steve).total)
        // The feed's news of it, later, pays nothing more.
        everyoneUntil("everyone heard") { a.plugin.stats(steve).total == 1 }
        for (h in all) h.platform.advance(3.seconds)
        assertEquals(1, diamonds(b))
    }

    @Test
    fun withNoMessageTheFeedStillPaysIt() {
        // Steve is on a, but the proxy's message can't reach a (nobody carries it there).
        a.join("Steve")
        proxy.join("Steve")
        proxy.plugin.switched(steve, "a")
        proxy.vote("Steve")
        a.platform.advance(0.seconds)
        everyoneUntil("Steve paid on a from the feed") { diamonds(a) == 1 }
    }

    @Test
    fun aVoteForAnOfflinePlayerIsQueuedAndPaidWhereTheyJoin() {
        onto(a, "a")
        a.leave("Steve")
        proxy.leave("Steve")
        wire.leave(steve)
        assertEquals(VoteResult.COUNTED, proxy.vote("Steve"))
        everyoneUntil("the vote known everywhere") { a.plugin.stats(steve).total == 1 && b.plugin.stats(steve).total == 1 }
        assertEquals(0, diamonds(a) + diamonds(b))
        // A name nobody has seen yet waits too.
        assertEquals(VoteResult.WAITING, proxy.vote("Alex"))
        onto(b, "b")
        b.platform.until("Steve paid on b, where he joined") { diamonds(b) == 1 }
        b.join("Alex")
        b.platform.until("Alex paid on b") { b.server.player("Alex").items.contains("diamond 1") }
        for (h in all) h.platform.advance(3.seconds)
        assertEquals(1, diamonds(b))
        assertEquals(0, diamonds(a))
    }

    @Test
    fun aJoinCheckedOnlyAfterThePlayerLeftTakesNothing() {
        // a's database is slow: its check of Steve's join runs after he left a and a vote for
        // him was stored, and whatever a would give back waits behind more of a's work. Taken
        // there, it'd be gone when Steve joins b, and b would pay it only from the feed.
        val first = java.util.concurrent.CountDownLatch(1)
        val second = java.util.concurrent.CountDownLatch(1)
        val db = a.plugin.backend!!.database
        db.transaction { first.await(); co.xenastudios.core.common.Outcome.Ok(Unit) }
        onto(a, "a")
        db.transaction { second.await(); co.xenastudios.core.common.Outcome.Ok(Unit) }
        try {
            a.leave("Steve")
            proxy.leave("Steve")
            wire.leave(steve)
            assertEquals(VoteResult.COUNTED, proxy.vote("Steve"))
            first.countDown()
            // a's join check is done once a reads Steve's voter row (it does, in the same step).
            a.platform.until("a's late join check") { a.plugin.stats(steve).total == 1 }
            onto(b, "b")
            // Only what's due on b runs: no feed, so this is b's own join.
            b.platform.until("Steve paid on b, where he joined") { diamonds(b) == 1 }
        } finally {
            second.countDown()
        }
        for (h in all) h.platform.advance(3.seconds)
        assertEquals(1, diamonds(b))
        assertEquals(0, diamonds(a))
    }

    @Test
    fun twoServersHearingTheSameVoteNeverBothPayIt() {
        // Steve shows as online on both (a switch in flight): both hear, only one takes it.
        a.join("Steve")
        b.join("Steve")
        proxy.join("Steve")
        proxy.plugin.switched(steve, "b")
        repeat(5) { i -> proxy.vote("Steve", "Site$i") }
        everyoneUntil("every vote paid somewhere") { diamonds(a) + diamonds(b) == 5 }
        for (h in all) h.platform.advance(5.seconds)
        assertEquals(5, diamonds(a) + diamonds(b), "paid exactly once each")
    }

    @Test
    fun aVoteGivenBackOnOneServerIsPaidOnTheNext() {
        a.join("Steve")
        // Steve leaves a the moment a vote for him arrives there: a gives it back, and he's on b.
        a.server.leave("Steve")
        assertEquals(VoteResult.COUNTED, a.vote("Steve"))
        a.plugin.quit(steve, "Steve")
        b.join("Steve")
        everyoneUntil("Steve paid on b") { diamonds(b) == 1 }
    }

    @Test
    fun thePartyCountsTheNetworksVotesAndEveryServerRewardsItsOwn() {
        val party = config + "party:\n  enabled: true\n  votes: 2\n  rewards: [vote]\n"
        for (h in all) h.reload(party)
        a.join("Alex")
        b.join("Steve")
        proxy.vote("Alex")
        proxy.vote("Steve")
        everyoneUntil("the party on both servers") {
            a.server.player("Alex").items.size >= 2 && b.server.player("Steve").items.size >= 2
        }
    }
}
