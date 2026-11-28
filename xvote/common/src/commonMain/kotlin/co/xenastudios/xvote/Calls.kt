package co.xenastudios.xvote

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.resolve
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xvote.api.PartyProgress
import co.xenastudios.xvote.api.Period
import co.xenastudios.xvote.api.RewardType
import co.xenastudios.xvote.api.TopVoter
import co.xenastudios.xvote.api.VoteResult
import co.xenastudios.xvote.api.VoteSource
import co.xenastudios.xvote.api.VoterStats
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.vote.IncomingVote
import kotlin.time.Duration.Companion.seconds

/**
 * What [XVotePlugin] does past its start: the API's calls, joins, reminders and held items. An object
 * of its own, loaded with the first of them, not as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal object Calls {
    /** The rest of [XVotePlugin.stop], off the server's threads: closing the receiver waits for its thread. */
    fun stop(plugin: XVotePlugin): Pending<Unit> {
        val done = newPending<Unit>()
        val receiver = plugin.receiver
        val votes = plugin.madeVotes
        val backend = plugin.backend
        plugin.platform.scheduler.run(Where.Async) { _ ->
            receiver?.close()
            if (backend == null) {
                done.resolve(Unit)
                return@run
            }
            backend.feed?.close()
            if (votes == null) {
                backend.database.close().onSettled { _, _ -> done.resolve(Unit) }
            } else {
                votes.shutdown().onSettled { _, _ -> backend.database.close().onSettled { _, _ -> done.resolve(Unit) } }
            }
        }
        return done
    }

    fun stats(plugin: XVotePlugin, player: PlayerRef): VoterStats {
        val voter = plugin.voters[player] ?: return VoterStats(player, "", 0, 0, 0, 0, 0, 0, emptyMap())
        val now = plugin.now()
        val periods = plugin.voters.periods
        return VoterStats(player, voter.name, voter.total, voter.today(periods, now), voter.week(periods, now), voter.month(periods, now),
            voter.streakNow(periods, now), voter.bestStreak, voter.lastBySite)
    }

    fun top(plugin: XVotePlugin, period: Period, limit: Int): List<TopVoter> {
        val ranked = plugin.voters.top(period, plugin.now())
        val count = minOf(maxOf(1, limit), ranked.size)
        return List(count) { i -> ranked[i].let { TopVoter(it.rank, it.voter.id, it.voter.name, it.votes) } }
    }

    fun party(plugin: XVotePlugin): PartyProgress {
        val votes = plugin.party.votes
        return PartyProgress(minOf(Int.MAX_VALUE.toLong(), votes).toInt(), if (plugin.config[XVoteConfig.party.enabled]) plugin.config[XVoteConfig.party.votes] else 0)
    }

    fun register(plugin: XVotePlugin, owner: String, id: String, type: RewardType) {
        require(id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "A reward type's id is 1 to 64 letters, digits, - and _; got \"$id\"."
        }
        plugin.rewardTypes[id.lowercase()] = Payout.Registered(owner, type)
    }

    fun submit(plugin: XVotePlugin, site: String, username: String, address: String): Pending<VoteResult> {
        val now = plugin.now()
        return plugin.accept(IncomingVote(site, username, address, now.toString(), VoteSource.API, now))
    }

    /** [player] joined (on a proxy, logged in): their votes held by name become theirs, and everything unpaid is paid; then a reminder. On their thread. */
    fun joined(plugin: XVotePlugin, player: PlayerRef, name: String) {
        val votes = plugin.votes()
        if (votes == null) {
            // Before the voters are read: the start treats everyone online as joining.
            plugin.online.add(player, name)
            return
        }
        votes.joined(player, name)
        if (!plugin.server.proxy && plugin.config[XVoteConfig.reminders.onJoin]) {
            val delay = plugin.config[XVoteConfig.reminders.joinDelaySeconds].coerceAtLeast(0)
            plugin.platform.scheduler.run(Where.On(player.uuid), delay = delay.seconds.coerceAtLeast(co.xenastudios.core.common.scheduler.TICK)) { _ -> plugin.remind(player) }
        }
    }

    /** The sites [player] can vote on now. */
    fun available(plugin: XVotePlugin, player: PlayerRef?, now: Long): List<Rewards.SiteEntry> {
        val voter = player?.let { plugin.voters[it] }
        return plugin.rewards.sites.filter { voter == null || voter.lastOn(it.service) + it.cooldownMs <= now }
    }

    /** Reminds [player] if they can vote. On their thread. */
    fun remind(plugin: XVotePlugin, player: PlayerRef) {
        if (plugin.server.name(player) == null || plugin.rewards.sites.isEmpty()) return
        val open = available(plugin, player, plugin.now()).size
        if (open > 0) plugin.texts.send(player, XVoteMessages.reminder, "available" to open)
    }

    /** Items that didn't fit: kept in the database for `/vote claim`. */
    fun hold(plugin: XVotePlugin, player: PlayerRef, items: List<ByteArray>, count: Int) {
        val db = plugin.backend?.database ?: return
        db.transaction { session -> co.xenastudios.xvote.store.Tx.run(session, db.sql) { tx -> co.xenastudios.xvote.store.Ledger.hold(tx, player, items, plugin.now()) } }
            .onSettled { done, error ->
                if (done is Outcome.Ok) {
                    plugin.texts.send(player, XVoteMessages.claimHeld, "count" to count)
                } else {
                    plugin.log.warn("Couldn't keep ${plugin.server.name(player) ?: player}'s reward items that didn't fit: ${(done as? Outcome.Err)?.error?.detail ?: error}")
                }
            }
    }

}
