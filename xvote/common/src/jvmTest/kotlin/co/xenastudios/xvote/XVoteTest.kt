package co.xenastudios.xvote

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xvote.api.Period
import co.xenastudios.xvote.api.RewardType
import co.xenastudios.xvote.api.VoteResult
import co.xenastudios.xvote.api.event.VoteCountedEvent
import co.xenastudios.xvote.api.event.VotePartyEvent
import co.xenastudios.xvote.api.event.VoteReceivedEvent
import co.xenastudios.xvote.api.event.VoteRewardEvent
import co.xenastudios.xvote.api.event.VoteRewardedEvent
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** One server: votes counted and paid once, held for names that haven't joined, repeats dropped, rewards, the party, the commands. */
class XVoteTest {
    private val rewards = """
        rewards:
          vote:
            items: ["diamond 1"]
            commands: ["give <player> emerald 1", "say <site> <total>"]
            message: "Thanks, <player>!"
            broadcast: "<player> voted on <site>."
            sound: "entity.player.levelup"
            money: 25
          streak:
            items: ["diamond 5"]
          party:
            items: ["golden_apple 1"]
            message: "Vote party!"
    """.trimIndent() + "\n"
    private val h = Harness(config = rewards).start()

    @AfterTest
    fun stop() {
        h.stop()
        h.folder.toFile().deleteRecursively()
    }

    private fun paid(name: String, count: Int) {
        h.platform.until("$name paid $count times") { h.server.players[FakeVoteServer.ref(name)]?.items?.count { it == "diamond 1" } == count }
    }

    @Test
    fun aVoteForAnOnlinePlayerIsCountedAndPaidOnce() {
        val steve = h.join("Steve")
        assertEquals(VoteResult.COUNTED, h.vote("Steve"))
        paid("Steve", 1)
        assertEquals(listOf("give Steve emerald 1", "say planetminecraft 1"), h.server.dispatched.toList())
        assertTrue("Thanks, Steve!" in steve.received)
        assertTrue(h.server.console.any { it == "Steve voted on Planet Minecraft." })
        assertEquals(listOf("entity.player.levelup"), h.server.sounds.toList())
        assertEquals(listOf(steve.ref to 25.0), h.economy.paid.toList())
        val stats = h.plugin.stats(steve.ref)
        assertEquals(1, stats.total)
        assertEquals(1, stats.today)
        assertEquals(1, stats.streak)
        // Joining again pays nothing more: it's been taken.
        h.leave("Steve")
        h.join("Steve")
        h.platform.advance(10.seconds)
        assertEquals(0, h.server.player("Steve").items.size, "nothing more on the second join")
        assertEquals(1, steve.items.count { it == "diamond 1" })
    }

    @Test
    fun aRepeatWithinTheSitesCooldownIsDropped() {
        h.join("Steve")
        assertEquals(VoteResult.COUNTED, h.vote("Steve"))
        assertEquals(VoteResult.DUPLICATE, h.vote("Steve"))
        // Another site counts.
        assertEquals(VoteResult.COUNTED, h.vote("Steve", "Minecraft-MP.com"))
        // An unlisted site has counting.unlisted-cooldown-hours (24).
        assertEquals(VoteResult.COUNTED, h.vote("Steve", "Some List"))
        assertEquals(VoteResult.DUPLICATE, h.vote("Steve", "some list"))
        // After the cooldown, less the five minutes' grace, it counts again.
        h.platform.advance(24.hours)
        assertEquals(VoteResult.COUNTED, h.vote("Steve"))
        assertEquals(4, h.plugin.stats(FakeVoteServer.ref("Steve")).total)
    }

    @Test
    fun aVoteForANameThatNeverJoinedWaitsForItAndIsPaidOnJoin() {
        assertEquals(VoteResult.WAITING, h.vote("Alex"))
        assertEquals(VoteResult.INVALID, h.vote("Not a name!"))
        assertEquals(0, h.plugin.top(Period.ALL, 10).size, "a name nobody has isn't on the leaderboards yet")
        h.join("Alex")
        paid("Alex", 1)
        assertEquals(1, h.plugin.stats(FakeVoteServer.ref("Alex")).total)
        assertEquals("Alex", h.plugin.top(Period.ALL, 10).single().name)
    }

    @Test
    fun aVoteForAPlayerWhoIsOfflineIsQueuedAndPaidWhenTheyJoin() {
        h.join("Steve")
        h.leave("Steve")
        assertEquals(VoteResult.COUNTED, h.vote("Steve"))
        h.platform.advance(5.seconds)
        assertEquals(0, h.server.dispatched.size, "nobody to pay yet")
        h.join("Steve")
        paid("Steve", 1)
    }

    @Test
    fun aPlayerWhoLeavesBeforeTheirRewardGivesTheVoteBack() {
        h.join("Steve")
        // Gone the moment the vote is stored: the payment finds nobody, and gives the vote back.
        h.server.leave("Steve")
        assertEquals(VoteResult.COUNTED, h.vote("Steve"))
        h.platform.advance(1.seconds)
        h.plugin.quit(FakeVoteServer.ref("Steve"), "Steve")
        h.platform.until("the vote given back") { h.plugin.votes!!.unpaidCount == 0 }
        h.join("Steve")
        paid("Steve", 1)
    }

    @Test
    fun bedrockPlayersWhoLeaveOffThePrefixArePaid() {
        assertEquals(VoteResult.WAITING, h.vote("Steve123"))
        h.join(".Steve123")
        h.platform.until("the Bedrock player paid") { h.server.player(".Steve123").items.contains("diamond 1") }
    }

    @Test
    fun streaksAndMilestones() {
        h.reload(rewards + "triggers:\n  every-vote: [vote]\n  streak:\n    2:\n      rewards: [streak]\n  total:\n    3:\n      rewards: [streak]\n")
        h.join("Steve")
        h.vote("Steve")
        h.platform.advance(24.hours)
        h.vote("Steve")
        h.platform.until("the streak reward") { h.server.player("Steve").items.contains("diamond 5") }
        assertEquals(2, h.plugin.stats(FakeVoteServer.ref("Steve")).streak)
        h.vote("Steve", "Minecraft-MP.com")
        h.platform.until("the total reward") { h.server.player("Steve").items.count { it == "diamond 5" } == 2 }
        // A day missed: the streak starts again.
        h.platform.advance(48.hours)
        h.vote("Steve")
        assertEquals(1, h.plugin.stats(FakeVoteServer.ref("Steve")).streak)
        assertEquals(2, h.plugin.stats(FakeVoteServer.ref("Steve")).bestStreak)
    }

    @Test
    fun itemsThatDontFitWaitForClaim() {
        val steve = h.join("Steve")
        steve.room = 0
        h.vote("Steve")
        h.platform.until("the held message") { steve.received.any { "didn't fit" in it } }
        steve.room = 10
        h.run("vote claim", h.server.source("Steve")) { steve.received.any { "Here are your 1 reward items" in it } }
        assertTrue("diamond 1" in steve.items)
        h.run("vote claim", h.server.source("Steve")) { steve.received.any { "no reward items waiting" in it } }
    }

    @Test
    fun theVotePartyRewardsEveryoneOnline() {
        h.reload(rewards + "party:\n  enabled: true\n  votes: 3\n  progress-every: 1\n")
        val steve = h.join("Steve")
        val alex = h.join("Alex")
        val parties = Collections.synchronizedList(ArrayList<Int>())
        h.plugin.events.subscribe(VotePartyEvent.TYPE) { parties += it.players.size }
        h.vote("Steve")
        h.vote("Alex")
        h.platform.until("the progress") { h.server.console.any { "1 more votes until the vote party" in it } }
        h.vote("Steve", "Minecraft-MP.com")
        h.platform.until("the party") { steve.items.contains("golden_apple 1") && alex.items.contains("golden_apple 1") }
        assertEquals(listOf(2), parties.toList())
        assertEquals(0, h.plugin.party().votes)
        assertTrue(h.server.console.any { "Vote party! 3 votes" in it })
    }

    @Test
    fun eventsAreFiredAndCanCancel() {
        val counted = Collections.synchronizedList(ArrayList<String>())
        val rewarded = Collections.synchronizedList(ArrayList<List<String>>())
        h.plugin.events.subscribe(VoteReceivedEvent.TYPE) { if (it.username == "Blocked") it.isCancelled = true }
        h.plugin.events.subscribe(VoteCountedEvent.TYPE) { counted += "${it.name}:${it.total}" }
        h.plugin.events.subscribe(VoteRewardEvent.TYPE) { it.rewards.clear(); it.rewards += "streak" }
        h.plugin.events.subscribe(VoteRewardedEvent.TYPE) { rewarded += it.rewards }
        assertEquals(VoteResult.CANCELLED, h.vote("Blocked"))
        val steve = h.join("Steve")
        h.vote("Steve")
        h.platform.until("the changed reward") { "diamond 5" in steve.items }
        assertEquals(listOf("Steve:1"), counted.toList())
        assertEquals(listOf(listOf("streak")), rewarded.toList())
        assertTrue("diamond 1" !in steve.items)
    }

    @Test
    fun rewardTypesFromOtherPlugins() {
        val given = Collections.synchronizedList(ArrayList<String>())
        h.plugin.registerRewardType("Crates", "crate", RewardType { player: PlayerRef, argument: String -> given += "$player $argument" })
        h.reload(rewards.replace("money: 25", "money: 0\n    custom: [\"crate vote <player>\", \"missing x\"]"))
        val steve = h.join("Steve")
        h.vote("Steve")
        h.platform.until("the crate") { given.isNotEmpty() }
        assertEquals(listOf("${steve.ref} vote Steve"), given.toList())
        assertTrue(h.platform.log.lines.any { "\"missing\"" in it.message })
    }

    @Test
    fun commands() {
        val steve = h.join("Steve")
        h.vote("Steve")
        h.run("vote", h.server.source("Steve")) { steve.received.any { "streak" in it } }
        assertTrue(steve.received.any { "1 of 2 sites are open" in it }, steve.received.toString())
        assertTrue(steve.received.any { "Planet Minecraft, again in" in it })
        h.run("vote top all")
        assertTrue(h.console.replies.any { "#1 Steve 1 votes" in it }, h.console.replies.toString())
        h.run("vote top week")
        h.run("xvote")
        assertTrue(h.console.replies.any { "xVote 2.0.0 on Test: 1 voters" in it }, h.console.replies.toString())
        h.run("xvote status") { h.console.replies.any { "Last votes" in it } }
        assertTrue(h.console.replies.any { "Receiver: off" in it || "Receiver: not listening" in it }, h.console.replies.toString())
        h.run("xvote party")
        assertTrue(h.console.replies.any { "The vote party is off" in it })
        h.run("vote test Alex") { h.console.replies.any { "Test vote for Alex" in it } }
        assertTrue(h.console.replies.any { "Nobody by that name has joined" in it }, h.console.replies.toString())
    }

    @Test
    fun forgetDeletesEverythingAboutAPlayer() {
        val steve = h.join("Steve")
        h.vote("Steve")
        paid("Steve", 1)
        h.run("xvote forget ${steve.ref}") { h.console.replies.any { "again" in it } }
        h.run("xvote forget ${steve.ref}") { h.console.replies.any { "Forgot" in it || "Deleted" in it } }
        assertEquals(0, h.plugin.stats(steve.ref).total)
    }
}
