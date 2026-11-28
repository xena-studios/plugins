package co.xenastudios.xvote

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyPayload
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.xvote.api.VoteResult
import co.xenastudios.xvote.api.VoteSource
import co.xenastudios.xvote.api.event.VoteCountedEvent
import co.xenastudios.xvote.api.event.VotePartyEvent
import co.xenastudios.xvote.api.event.VoteReceivedEvent
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.store.Ledger
import co.xenastudios.xvote.store.Reads
import co.xenastudios.xvote.store.Tx
import co.xenastudios.xvote.vote.Facts
import co.xenastudios.xvote.vote.IncomingVote
import co.xenastudios.xvote.vote.Names
import co.xenastudios.xvote.vote.Voter
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/** The database the votes are in, and the network's change feed if it's on. */
public class Backend(public val database: Database, public val feed: ChangeFeed?) {
    /** Whether the servers share it: network mode is on. */
    public val shared: Boolean get() = feed != null
}

/**
 * Where every vote goes: from the receiver, `/vote test`, the API and a proxy's NuVotifier. It
 * checks a vote, stores it, counts it for its player, and pays it once (decisions/2026-11-27-xvote-2.md).
 *
 * **Never twice.** A vote is paid by the server that takes it: one `UPDATE ... SET claim = ? WHERE
 * claim IS NULL`, in the database, so two servers sharing it can't both take one. A vote for a
 * player online here is taken as it's stored; one for a player elsewhere on the network is taken by
 * their server when it hears of it (the proxy's message, or the change feed); the rest wait in the
 * database, and the first server the player joins takes them. A vote is taken before it's paid, so
 * a crash in between loses that reward rather than paying it again; a player who leaves before
 * their reward reaches their thread gives the vote back, untaken, for the next server they're on.
 *
 * **The proxy never pays.** It has no items, commands or economy: it stores and counts what it
 * receives, and tells the server the player is on, which takes and pays it.
 *
 * Every database step is one transaction on the database's writer, in order. Memory (totals,
 * leaderboards, the party's count) is updated after it commits.
 */
internal class Votes(private val plugin: XVotePlugin, val backend: Backend) {
    private val db = backend.database
    private val feed = backend.feed
    private val origin = kotlin.uuid.Uuid.random().toHexString().substring(0, 12)
    private var claims = 0L
    private val tests = concurrentMap<String, Pending<VoteResult>>()

    // Votes this run has taken to pay and not paid yet, by id, with their claim.
    private val unpaid = concurrentMap<Long, String>()

    @Volatile
    private var stopping = false

    var party: Reads.PartyState
        get() = plugin.party
        private set(value) {
            plugin.party = value
        }

    private val voters get() = plugin.voters
    private val online get() = plugin.online
    private val pays: Boolean get() = !plugin.server.proxy

    init {
        // Made with the first vote or join, not as the server starts: what's left of 1.x's
        // upkeep at setup is done then.
        forgetUnknown(plugin.now())
    }

    /** A claim no other server or moment can have: this run's random id and a counter. */
    fun newClaim(): String = origin + "-" + locked(this) { ++claims }.toString(36)

    internal fun siteWindow(): Long = maxOf(SITE_WINDOW_MS, plugin.rewards.longestCooldown)

    /**
     * This server may have missed other servers' news (it couldn't reach the database for a
     * while): reads the leaderboards and the party again, and pays everyone online here whatever
     * is waiting for them, as a join would. Claims make it safe to run any time.
     */
    fun resync() {
        plugin.loadVoters().onSettled { _, _ ->
            for ((player, name) in online.all()) joined(player, name)
        }
    }

    // ---- votes arriving ----

    /** `/vote test` is about to send this vote through the receiver: when it arrives it's marked a test, and its result settles the [Pending]. */
    fun expectTest(site: String, username: String, timestamp: String): Pending<VoteResult> {
        val pending = newPending<VoteResult>()
        val key = testKey(site, username, timestamp)
        tests[key] = pending
        plugin.platform.scheduler.later(Where.Async, 30.seconds) { _ -> if (tests.remove(key) != null) pending.resolve(VoteResult.FAILED) }
        return pending
    }

    private fun testKey(site: String, username: String, timestamp: String) = "$site\n$username\n$timestamp"

    /** Checks, stores, counts and pays a vote. Any thread; nothing here waits for the database. */
    fun accept(arrived: IncomingVote): Pending<VoteResult> {
        val test = tests.remove(testKey(arrived.site, arrived.username, arrived.timestamp))
        val vote = if (test == null) arrived else arrived.from(VoteSource.TEST)
        val result = handle(vote)
        if (test != null) result.onSettled { value, _ -> test.resolve(value ?: VoteResult.FAILED) }
        return result
    }

    private fun handle(vote: IncomingVote): Pending<VoteResult> {
        if (plugin.hasListeners(VoteReceivedEvent.TYPE)) {
            val event = plugin.bus.fire(VoteReceivedEvent.TYPE, VoteReceivedEvent(vote.site, vote.username, vote.address, vote.timestamp, vote.source))
            if (event.isCancelled) {
                log(vote, vote.username, "dropped by another plugin")
                return pendingOf(VoteResult.CANCELLED)
            }
        }
        val prefix = plugin.config[XVoteConfig.counting.bedrockPrefix]
        val name = Names.clean(vote.username, prefix)
        if (name == null) {
            log(vote, vote.username, "dropped: \"${vote.username}\" can't be a Minecraft name")
            return pendingOf(VoteResult.INVALID)
        }
        val player = resolve(name, prefix)
        val result = newPending<VoteResult>()
        db.transaction { session -> Tx.run(session, db.sql) { tx -> record(tx, vote, player, name) } }.onSettled { stored, error ->
            when (stored) {
                is Outcome.Ok -> result.resolve(after(vote, stored.value))
                else -> {
                    plugin.log.warn("Couldn't store a vote from ${vote.site} for $name: ${(stored as? Outcome.Err)?.error?.detail ?: error}")
                    result.resolve(VoteResult.FAILED)
                }
            }
        }
        return result
    }

    /** The player a name belongs to, from memory: online (here, or on a proxy the network), voted before, or known to the server. */
    fun resolve(name: String, prefix: String): Pair<PlayerRef, String>? {
        for (candidate in Names.lookups(name, prefix)) {
            val here = online.byName(candidate)
            // Read once: a player quitting just now has no name any more, and isn't online.
            val hereName = here?.let(online::name)
            if (here != null && hereName != null) return here to hereName
            val voted = voters.byName(candidate)
            val voter = voted?.let { voters[it] }
            if (voter != null) return voter.id to voter.name
            plugin.server.known(candidate)?.let { return it }
        }
        return null
    }

    /** What storing a vote did. */
    private class Stored(
        val result: VoteResult,
        val facts: Facts?,
        val claim: String?,
        val voter: Voter?,
        val party: Reads.PartyState?,
        val partyFrom: Long,
        val left: Long,
    )

    private fun record(tx: Tx, vote: IncomingVote, player: Pair<PlayerRef, String>?, name: String): Stored {
        val rewards = plugin.rewards
        // A test from a site that isn't listed (/vote test without a site) can be sent again and again.
        var cooldown = if (vote.source == VoteSource.TEST && rewards.site(vote.site) == null) 0L else rewards.cooldown(vote.site)
        cooldown = if (cooldown > 0) maxOf(0L, cooldown - GRACE_MS) else 0L
        val id = player?.first
        val shown = player?.second ?: name
        if (id != null) Ledger.lock(tx, id)
        if (Ledger.isRepeat(tx, id, Names.key(shown), vote, cooldown)) return Stored(VoteResult.DUPLICATE, null, null, null, null, -1, -1)
        val claim = if (id != null && pays && online.has(id) && !stopping) newClaim() else null
        val voteId = Ledger.insert(tx, vote, id, shown, claim)
        val facts = id?.let { Ledger.count(tx, voteId, it, shown, vote.site, vote.receivedAt, voters.periods) }
        tx.publish(feed, "vote", voteId.toString())

        var after: Reads.PartyState? = null
        var partyFrom = -1L
        var left = -1L
        val config = plugin.config
        if (config[XVoteConfig.party.enabled]) {
            val needed = config[XVoteConfig.party.votes]
            val before = Reads.party(tx, true)
            val since = if (before.since == 0L) vote.receivedAt else before.since
            val count = before.votes + 1
            if (count >= needed) {
                partyFrom = since
                // The next round starts after this vote: its voter was rewarded by this party, and
                // isn't again by the next unless they vote again.
                after = Reads.PartyState(0, vote.receivedAt + 1)
                tx.publish(feed, "party", "start:$since")
            } else {
                after = Reads.PartyState(count, since)
                val remaining = needed - count
                val every = config[XVoteConfig.party.progressEvery]
                if (every > 0 && remaining % every == 0L) {
                    left = remaining
                    tx.publish(feed, "party", "left:$remaining")
                }
            }
            Ledger.party(tx, after)
        }
        val voter = id?.let { Reads.voter(tx, it, voters.periods, vote.receivedAt, siteWindow()) }
        return Stored(if (id == null) VoteResult.WAITING else VoteResult.COUNTED, facts, claim, voter, after, partyFrom, left)
    }

    private fun after(vote: IncomingVote, stored: Stored): VoteResult {
        stored.party?.let { party = it }
        stored.voter?.let(voters::put)
        val facts = stored.facts
        val who = facts?.name ?: vote.username
        when {
            stored.result == VoteResult.DUPLICATE -> log(vote, who, "dropped: a repeat within the site's cooldown")
            stored.result == VoteResult.WAITING -> log(vote, who, "held until $who first joins")
            stored.claim != null -> log(vote, who, "paid")
            plugin.server.proxy && facts != null && online.server(facts.player) != null ->
                log(vote, who, "counted; paid on ${online.server(facts.player)}, where $who is")
            else -> log(vote, who, "counted; paid when $who is next online")
        }
        if (facts != null) counted(facts)
        if (stored.claim != null && facts != null) pay(facts.player, listOf(facts), stored.claim)
        if (facts != null && stored.claim == null && plugin.server.proxy) forward(facts)
        if (pays) {
            if (stored.partyFrom >= 0) party(stored.partyFrom) else if (stored.left >= 0) progress(stored.left)
        }
        return stored.result
    }

    private fun counted(facts: Facts) {
        if (plugin.hasListeners(VoteCountedEvent.TYPE)) {
            plugin.bus.fire(VoteCountedEvent.TYPE, VoteCountedEvent(facts.player, facts.name, facts.site, facts.number, facts.streak, facts.firstToday, facts.at))
        }
    }

    /**
     * The proxy only: tells the server [facts]' player is on that a vote of theirs is waiting, so it
     * takes and pays it at once. The change feed carries the same news within `check-every`, and
     * their next join anywhere takes it too, so a message that doesn't arrive loses nothing.
     */
    private fun forward(facts: Facts) {
        val channel = plugin.channel ?: return
        val server = online.server(facts.player) ?: return
        channel.send(Peer.Server(server), TOPIC_VOTE, ProxyPayload.write { int(1); long(facts.id) })
    }

    private fun log(vote: IncomingVote, name: String, outcome: String) {
        plugin.log.info("Vote for $name from ${vote.site} (${source(vote.source)}): $outcome.")
    }

    private fun source(source: VoteSource): String = when (source) {
        VoteSource.VOTIFIER_V1 -> "v1"
        VoteSource.VOTIFIER_V2 -> "v2"
        VoteSource.TEST -> "test"
        VoteSource.API -> "plugin"
        VoteSource.FORWARDED -> "forwarded"
    }

    // ---- paying ----

    private fun pay(player: PlayerRef, votes: List<Facts>, claim: String) {
        for (facts in votes) unpaid[facts.id] = claim
        if (stopping) return // Given back by shutdown().
        val payment = Payment(player, votes, claim)
        plugin.platform.scheduler.run(Where.On(player.uuid), retired = payment, action = payment)
    }

    // Paying on the player's thread, or giving the votes back if they left first: a class, not two lambdas.
    private inner class Payment(private val player: PlayerRef, private val votes: List<Facts>, private val claim: String) : (Task) -> Unit, () -> Unit {
        override fun invoke(task: Task) {
            if (plugin.server.name(player) == null) return invoke()
            for (facts in votes) {
                if (unpaid.remove(facts.id) != null) plugin.payout().vote(player, facts)
            }
        }

        override fun invoke() {
            val back = ArrayList<Long>()
            for (facts in votes) if (unpaid.remove(facts.id) != null) back += facts.id
            giveBack(back, claim)
        }
    }

    // Claimed votes go back unpaid, for the next server the player is on: told of each again, so
    // the one they're on now (they switched) takes it.
    private fun giveBack(ids: List<Long>, claim: String) {
        if (ids.isEmpty()) return
        db.transaction { session ->
            Tx.run(session, db.sql) { tx ->
                for (id in ids) {
                    Ledger.unclaim(tx, id, claim)
                    tx.publish(feed, "vote", id.toString())
                }
            }
        }
    }

    /**
     * xVote is stopping: votes taken to pay that haven't been paid yet go back, so the next start
     * (or another server) pays them. Each vote is either paid or given back, never both. Before the
     * database closes.
     */
    fun shutdown(): Pending<Unit> {
        stopping = true
        val done = newPending<Unit>()
        // Lets the work already queued finish, so the votes it takes are in unpaid too.
        db.session { Outcome.Ok(Unit) }.onSettled { _, _ ->
            val byClaim = HashMap<String, MutableList<Long>>()
            for (id in unpaid.keys.toList()) {
                val claim = unpaid.remove(id) ?: continue
                byClaim.getOrPut(claim) { ArrayList() } += id
            }
            val count = byClaim.values.sumOf { it.size }
            if (count > 0) {
                plugin.log.info("Gave back $count votes that weren't paid yet; they're paid when their player is next online.")
                var left = byClaim.size
                for ((claim, ids) in byClaim) {
                    db.transaction { session -> Tx.run(session, db.sql) { tx -> for (id in ids) Ledger.unclaim(tx, id, claim) } }.onSettled { _, _ ->
                        if (locked(byClaim) { --left } == 0) done.resolve(Unit)
                    }
                }
            } else {
                done.resolve(Unit)
            }
        }
        return done
    }

    // ---- players joining ----

    private class Joined(val voter: Voter?, val unpaid: List<Facts>, val claim: String, val matched: List<Facts>)

    /** A player joined: their unmatched votes become theirs, and everything unpaid is paid. On a proxy, only who's on is kept. */
    fun joined(player: PlayerRef, name: String) {
        online.add(player, name)
        voters.joinedAs(player, name)
        if (!pays) return
        val keys = Names.matchKeys(name, plugin.config[XVoteConfig.counting.bedrockPrefix])
        val now = plugin.now()
        val periods = voters.periods
        val window = siteWindow()
        db.transaction { session ->
            Tx.run(session, db.sql) { tx ->
                val matched = Ledger.match(tx, player, name, keys, periods)
                if (matched.isNotEmpty()) tx.publish(feed, "player", player.toString())
                Ledger.rename(tx, player, name)
                val claim = newClaim()
                // Not if they've left already (this ran late): what it took would only go back,
                // after the server they're on now looked and found it taken, so they'd wait for
                // the feed. Left now, it's there untaken for that server's join.
                val unpaid = if (stopping || !online.has(player)) emptyList() else Ledger.claimUnpaid(tx, player, claim, now)
                Joined(Reads.voter(tx, player, periods, now, window), unpaid, claim, matched)
            }
        }.onSettled { joined, error ->
            if (joined !is Outcome.Ok) {
                plugin.log.warn("Couldn't check $name's waiting votes: ${(joined as? Outcome.Err)?.error?.detail ?: error}")
                return@onSettled
            }
            val value = joined.value
            value.voter?.let(voters::put)
            if (value.matched.isNotEmpty()) {
                plugin.log.info("$name joined: ${value.matched.size} votes held for their name are now theirs.")
                for (facts in value.matched) counted(facts)
            }
            if (value.unpaid.isNotEmpty()) {
                plugin.log.info("Paying $name for ${value.unpaid.size} votes from while they were away.")
                pay(player, value.unpaid, value.claim)
            }
        }
    }

    fun quit(player: PlayerRef, name: String) {
        online.remove(player, name)
    }

    // ---- news from the other servers ----

    private class Heard(val player: PlayerRef?, val voter: Voter?, val unpaid: List<Facts>, val claim: String?, val party: Reads.PartyState, val matched: List<Facts>)

    /** A vote was stored elsewhere (another server, or the proxy): count it here, and pay it if its player is online here. */
    fun heard(voteId: Long) {
        val prefix = plugin.config[XVoteConfig.counting.bedrockPrefix]
        val now = plugin.now()
        val periods = voters.periods
        val window = siteWindow()
        db.transaction { session ->
            Tx.run(session, db.sql) { tx ->
                val row = Ledger.read(tx, voteId)
                val partyNow = Reads.party(tx, false)
                if (row == null) return@run Heard(null, null, emptyList(), null, partyNow, emptyList())
                var player = row.player
                var matched = emptyList<Facts>()
                if (player == null) {
                    // Its name may be online here though the other server didn't know it.
                    val name = Names.clean(row.name, prefix)
                    var here = name?.let(online::byName)
                    if (here == null && name != null && prefix.isNotEmpty() && !name.startsWith(prefix)) here = online.byName(prefix + name)
                    val hereName = here?.let(online::name)
                    // Not online here, or quit just now: matched when they next join.
                    if (here == null || hereName == null || !pays) return@run Heard(null, null, emptyList(), null, partyNow, emptyList())
                    matched = Ledger.match(tx, here, hereName, Names.matchKeys(hereName, prefix), periods)
                    if (matched.isNotEmpty()) tx.publish(feed, "player", here.toString())
                    player = here
                }
                var claim: String? = null
                var unpaid = emptyList<Facts>()
                if (pays && online.has(player) && !stopping) {
                    claim = newClaim()
                    unpaid = Ledger.claimUnpaid(tx, player, claim, now)
                }
                Heard(player, Reads.voter(tx, player, periods, now, window), unpaid, claim, partyNow, matched)
            }
        }.onSettled { heard, error ->
            if (heard !is Outcome.Ok) {
                plugin.log.warn("Couldn't read a vote another server stored: ${(heard as? Outcome.Err)?.error?.detail ?: error}")
                return@onSettled
            }
            val value = heard.value
            party = value.party
            value.voter?.let(voters::put)
            for (facts in value.matched) counted(facts)
            val claim = value.claim
            if (value.unpaid.isNotEmpty() && value.player != null && claim != null) pay(value.player, value.unpaid, claim)
        }
    }

    /** Another server changed a player's votes: read them again. */
    fun refresh(player: PlayerRef) {
        val now = plugin.now()
        val periods = voters.periods
        val window = siteWindow()
        db.read { session -> Tx.read(session, db.sql) { tx -> Reads.voter(tx, player, periods, now, window) } }.onSettled { voter, _ ->
            ((voter as? Outcome.Ok)?.value)?.let(voters::put)
        }
    }

    fun partyNews(payload: String) {
        when {
            payload.startsWith("start:") -> {
                party = Reads.PartyState(0, plugin.now())
                if (pays) party(payload.substring(6).trim().toLongOrNull() ?: 0L)
            }
            payload.startsWith("left:") -> {
                val left = payload.substring(5).trim().toLongOrNull() ?: return
                party = Reads.PartyState(maxOf(0L, plugin.config[XVoteConfig.party.votes] - left), party.since)
                if (pays) progress(left)
            }
        }
    }

    // ---- the party ----

    /** A vote party starts on this server; [since] is when its round began. On the global thread. */
    fun party(since: Long) {
        plugin.platform.scheduler.run(Where.Global, action = PartyRun(since))
    }

    private inner class PartyRun(private val since: Long) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val config = plugin.config
            val everyone = config[XVoteConfig.party.who] == XVoteConfig.Who.ONLINE
            val chosen = ArrayList<PlayerRef>()
            for (player in plugin.server.online()) {
                if (everyone || (voters[player]?.lastVote ?: -1) >= since) chosen += player
            }
            if (plugin.hasListeners(VotePartyEvent.TYPE)) {
                val event = plugin.bus.fire(VotePartyEvent.TYPE, VotePartyEvent(chosen))
                if (event.isCancelled) return
            }
            plugin.log.info("Vote party! Rewarding ${chosen.size} players.")
            plugin.texts.broadcast(XVoteMessages.partyStarted, "votes" to config[XVoteConfig.party.votes])
            val rewards = plugin.rewards
            val now = plugin.now()
            for (player in chosen.toList()) {
                val voter = voters[player]
                val context = Payout.Context("party", "party", voter?.total ?: 0, voter?.streakNow(voters.periods, now) ?: 0)
                plugin.platform.scheduler.run(Where.On(player.uuid)) { _ -> plugin.payout().give(player, rewards, rewards.party, context) }
            }
        }
    }

    /** [left] votes to go until the party. */
    fun progress(left: Long) {
        plugin.texts.broadcast(XVoteMessages.partyProgress, "left" to left)
    }

    // ---- staff ----

    /** Starts a party now, as if the last vote it needed had arrived. */
    fun startParty(): Pending<Outcome<Unit, DbError>> {
        val now = plugin.now()
        val done = newPending<Outcome<Unit, DbError>>()
        db.transaction { session ->
            Tx.run(session, db.sql) { tx ->
                val before = Reads.party(tx, true)
                val since = if (before.since == 0L) now else before.since
                Ledger.party(tx, Reads.PartyState(0, now))
                tx.publish(feed, "party", "start:$since")
                since
            }
        }.onSettled { since, error ->
            if (since is Outcome.Ok) {
                party = Reads.PartyState(0, now)
                if (pays) party(since.value)
                done.resolve(Outcome.Ok(Unit))
            } else {
                done.resolve(Outcome.Err((since as? Outcome.Err)?.error ?: DbError(DbError.Kind.UNAVAILABLE, error.toString())))
            }
        }
        return done
    }

    /** Deletes votes for names that never joined, past the configured days. */
    fun forgetUnknown(now: Long) {
        val days = plugin.config[XVoteConfig.counting.unknownNameDays]
        if (days <= 0) return
        db.transaction { session -> Tx.run(session, db.sql) { tx -> Ledger.forgetUnknown(tx, now - days * 86_400_000L) } }.onSettled { deleted, _ ->
            val count = (deleted as? Outcome.Ok)?.value ?: 0
            if (count > 0) plugin.log.info("Forgot $count votes for names that didn't join within $days days.")
        }
    }

    /** Votes taken to pay and not paid yet, for tests. */
    internal val unpaidCount: Int get() = unpaid.size

    companion object {
        /** Cooldowns allow this much early, for lists whose clocks run a little fast. */
        const val GRACE_MS = 5 * 60_000L

        /** Each player's last vote per site is kept at least this far back, for /vote. */
        const val SITE_WINDOW_MS = 48 * 3_600_000L

        /** The proxy channel's topic: a vote of a player on that server is waiting. */
        const val TOPIC_VOTE = "vote"
    }
}
