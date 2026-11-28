package co.xenastudios.xvote

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.ChatPages
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.time.Durations
import co.xenastudios.xvote.api.Period
import co.xenastudios.xvote.api.VoteResult
import co.xenastudios.xvote.api.VoteSource
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.store.Ledger
import co.xenastudios.xvote.store.Tx
import co.xenastudios.xvote.vote.IncomingVote
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/** What xVote's commands do: 1.x's `VoteCommand` and `AdminCommands`. Made the first time a command runs, not as the server starts. */
internal class Actions private constructor(private val plugin: XVotePlugin) {
    private val texts get() = plugin.texts

    @Volatile
    private var importing = false

    fun info(source: CommandSource) {
        texts.reply(
            source, if (plugin.server.proxy) XVoteMessages.proxyInfo else XVoteMessages.info,
            "version" to plugin.version, "platform" to plugin.platform.name, "voters" to plugin.voters.size,
            "storage" to (plugin.backend?.database?.description ?: plugin.failure ?: "no database yet"),
        )
    }

    // ---- /vote ----

    fun list(source: CommandSource) {
        val rewards = plugin.rewards
        if (rewards.sites.isEmpty()) return texts.reply(source, XVoteMessages.listNone)
        val now = plugin.now()
        val player = source.player
        val voter = player?.let { plugin.voters[it] }
        val language = texts.language(player)
        val lines = ArrayList<co.xenastudios.core.common.text.Component>()
        var open = 0
        for (site in rewards.sites) {
            val next = (voter?.lastOn(site.service) ?: 0L) + site.cooldownMs
            val link = Placeholder.text(site.link.replace("'", "%27"))
            val line = if (voter == null || next <= now) {
                open++
                texts.render(language, XVoteMessages.listAvailable, listOf("site" to site.name, "link" to link))
            } else {
                texts.render(language, XVoteMessages.listCooldown, listOf("site" to site.name, "link" to link, "time" to Durations.format((next - now).milliseconds)))
            }
            line?.let(lines::add)
        }
        texts.reply(source, XVoteMessages.listHeader, "available" to open, "sites" to rewards.sites.size)
        for (line in lines) source.reply(line)
        if (player != null) {
            texts.reply(source, XVoteMessages.listFooter, "total" to (voter?.total ?: 0), "streak" to (voter?.streakNow(plugin.voters.periods, now) ?: 0))
        }
        val config = plugin.config
        if (config[XVoteConfig.party.enabled]) {
            val votes = plugin.party.votes
            val needed = config[XVoteConfig.party.votes]
            texts.reply(source, XVoteMessages.listParty, "votes" to votes, "needed" to needed, "left" to maxOf(0L, needed - votes))
        }
    }

    // ---- /vote top [period] [page] ----

    fun top(source: CommandSource, first: String?, pageArg: Int?) {
        var period = Period.MONTH
        var page = pageArg ?: 1
        if (first != null) {
            val asPage = first.toIntOrNull()
            if (asPage != null) {
                page = asPage.coerceIn(1, 100_000)
            } else {
                period = when (first.lowercase()) {
                    "day", "today" -> Period.DAY
                    "week" -> Period.WEEK
                    "month" -> Period.MONTH
                    "all", "alltime", "total" -> Period.ALL
                    else -> return texts.reply(source, CoreMessages.commandUsage, "usage" to "/vote top [day|week|month|all] [page]")
                }
            }
        }
        val name = when (period) {
            Period.DAY -> XVoteMessages.periodDay
            Period.WEEK -> XVoteMessages.periodWeek
            Period.MONTH -> XVoteMessages.periodMonth
            Period.ALL -> XVoteMessages.periodAll
        }
        val periodName = texts.word(source.player, name)
        val ranked = plugin.voters.top(period, plugin.now())
        if (ranked.isEmpty()) return texts.reply(source, XVoteMessages.topEmpty, "period" to periodName)
        val shown = ChatPages.of(ranked, page, PER_PAGE)
        texts.reply(source, XVoteMessages.topHeader, "period" to periodName)
        for (entry in shown.items) texts.reply(source, XVoteMessages.topLine, "rank" to entry.rank, "player" to entry.voter.name, "votes" to entry.votes)
        if (shown.pages > 1) {
            val language = texts.language(source.player)
            val footer = ChatPages.footer(shown, "/vote top ${period.name.lowercase()} {page}", language)
            texts.render(language, CoreMessages.pagesFooter, footer.entries.map { it.key to it.value as Any })?.let(source::reply)
        }
    }

    // ---- /vote claim ----

    fun claim(source: CommandSource) {
        val player = source.player ?: return
        val backend = plugin.backend ?: return texts.reply(source, CoreMessages.commandError)
        val db = backend.database
        db.transaction { session -> Tx.run(session, db.sql) { tx -> Ledger.takeHeld(tx, player) } }.onSettled { items, error ->
            if (items !is Outcome.Ok) {
                plugin.log.warn("Couldn't read ${source.name}'s held items: ${(items as? Outcome.Err)?.error?.detail ?: error}")
                return@onSettled texts.reply(source, CoreMessages.commandError)
            }
            val held = items.value
            if (held.isEmpty()) return@onSettled texts.reply(source, XVoteMessages.claimNone)
            // Taken out of the database already: if the player has gone, they go back.
            val back = { putBack(player, held) }
            plugin.platform.scheduler.run(Where.On(player.uuid), retired = back) { _ ->
                val (given, leftover, count) = plugin.server.handOut(player, held)
                if (given > 0) texts.send(player, XVoteMessages.claimClaimed, "count" to given)
                if (leftover.isNotEmpty()) {
                    putBack(player, leftover)
                    texts.send(player, XVoteMessages.claimStillFull, "count" to count)
                }
            }
        }
    }

    private fun putBack(player: PlayerRef, items: List<ByteArray>) {
        val db = plugin.backend?.database ?: return
        db.transaction { session -> Tx.run(session, db.sql) { tx -> Ledger.hold(tx, player, items, plugin.now()) } }
    }

    // ---- /vote test <player> [site] ----

    fun test(source: CommandSource, name: String, siteArg: String?) {
        val site = siteArg?.let { plugin.rewards.siteByKey(it) }
        val service = site?.service ?: siteArg ?: "test"
        val now = plugin.now()
        val timestamp = now.toString()
        val votes = plugin.votes() ?: return texts.reply(source, XVoteMessages.testFailed, "player" to name)
        plugin.platform.scheduler.run(Where.Async) { _ ->
            val receiver = plugin.receiver
            val status = receiver?.status
            var via: Any = XVoteMessages.viaOff
            val result = if (receiver != null && status != null && status.listening) {
                val expected = votes.expectTest(service, name, timestamp)
                val sent = receiver.sendTest(service, name, timestamp)
                when {
                    sent == null -> {
                        via = Texts.Word(XVoteMessages.viaReceiver, "port" to status.port.toString())
                        expected
                    }
                    !sent.unreachable -> return@run texts.reply(source, XVoteMessages.testRefused, "reason" to sent.reason)
                    else -> {
                        texts.reply(source, XVoteMessages.testUnreachable, "port" to status.port.toString(), "reason" to sent.reason)
                        via = XVoteMessages.viaDirect
                        plugin.accept(IncomingVote(service, name, "127.0.0.1", timestamp, VoteSource.TEST, now))
                    }
                }
            } else {
                plugin.accept(IncomingVote(service, name, "127.0.0.1", timestamp, VoteSource.TEST, now))
            }
            result.onSettled { outcome, _ -> describe(source, outcome ?: VoteResult.FAILED, name, service, via) }
        }
    }

    private fun describe(source: CommandSource, result: VoteResult, name: String, service: String, via: Any) {
        when (result) {
            VoteResult.COUNTED -> texts.reply(
                source, XVoteMessages.testCounted, "player" to name, "site" to service, "via" to via,
                "then" to if (plugin.online.byName(name) != null && !plugin.server.proxy) XVoteMessages.testPaidNow else XVoteMessages.testPaidLater,
            )
            VoteResult.WAITING -> texts.reply(source, XVoteMessages.testWaiting, "player" to name, "site" to service, "via" to via)
            VoteResult.DUPLICATE -> texts.reply(source, XVoteMessages.testDuplicate, "player" to name, "site" to service)
            VoteResult.INVALID -> texts.reply(source, XVoteMessages.testInvalid, "player" to name)
            VoteResult.CANCELLED -> texts.reply(source, XVoteMessages.testCancelled, "player" to name)
            VoteResult.FAILED -> texts.reply(source, XVoteMessages.testFailed, "player" to name)
        }
    }

    // ---- /xvote status ----

    fun status(source: CommandSource) {
        val receiver = plugin.receiver?.status
        val config = plugin.config
        val listening: Any = when {
            receiver != null && !receiver.listening -> Texts.Word(XVoteMessages.statusStopped, "port" to receiver.port.toString())
            receiver != null -> Texts.Word(XVoteMessages.statusListening, "port" to receiver.port.toString(), "versions" to if (receiver.v1) "v1 and v2" else "v2 only", "tokens" to receiver.tokens)
            config[XVoteConfig.receiver.enabled] -> XVoteMessages.statusNotListening
            else -> XVoteMessages.statusOff
        }
        val backend = plugin.backend
        val storage = (backend?.database?.description ?: plugin.failure ?: "not open") + if (backend?.shared == true) texts.word(source.player, XVoteMessages.statusNetwork) else ""
        val party: Any = if (config[XVoteConfig.party.enabled]) Texts.Word(XVoteMessages.statusPartyOn, "votes" to (plugin.party.votes), "needed" to config[XVoteConfig.party.votes]) else XVoteMessages.statusPartyOff
        texts.reply(source, XVoteMessages.statusHeader, "receiver" to listening, "storage" to storage, "voters" to plugin.voters.size, "party" to party)
        if (plugin.server.proxy && backend != null && !backend.shared) texts.reply(source, XVoteMessages.proxyNoNetwork)
        val db = backend?.database ?: return
        db.read { session -> Tx.read(session, db.sql) { tx -> Ledger.waiting(tx) to Ledger.recent(tx, 5) } }.onSettled { found, error ->
            if (found !is Outcome.Ok) return@onSettled texts.reply(source, XVoteMessages.statusFailed, "reason" to ((found as? Outcome.Err)?.error?.detail ?: error.toString()))
            val (waiting, recent) = found.value
            texts.reply(source, XVoteMessages.statusWaiting, "unpaid" to waiting[0], "unknown" to waiting[1])
            if (recent.isEmpty()) return@onSettled
            texts.reply(source, XVoteMessages.statusRecent)
            val now = plugin.now()
            for (vote in recent) {
                val state = if (vote.paid) XVoteMessages.statusPaid else if (vote.matched) XVoteMessages.statusUnpaid else XVoteMessages.statusUnknownName
                texts.reply(
                    source, XVoteMessages.statusVote, "ago" to Durations.format((now - vote.at).milliseconds), "player" to vote.name, "site" to vote.site,
                    "source" to vote.source.lowercase().replace('_', ' '), "state" to state,
                )
            }
        }
    }

    // ---- /xvote key and token ----

    fun key(source: CommandSource) {
        plugin.platform.scheduler.run(Where.Async) { _ ->
            val receiver = plugin.receiver ?: return@run texts.reply(source, XVoteMessages.keyNone)
            val key = receiver.publicKey() ?: return@run texts.reply(source, XVoteMessages.keyNone)
            texts.reply(source, if (source.player != null) XVoteMessages.keyShown else XVoteMessages.keyConsole, "key" to key, "file" to receiver.keyFile)
        }
    }

    fun token(source: CommandSource, wanted: String?) {
        plugin.platform.scheduler.run(Where.Async) { _ ->
            val receiver = plugin.receiver ?: return@run texts.reply(source, XVoteMessages.tokenNone)
            val tokens = receiver.tokens() ?: return@run texts.reply(source, XVoteMessages.tokenNone)
            if (wanted == null) {
                texts.reply(source, XVoteMessages.tokenList)
                for ((service, value) in tokens) texts.reply(source, XVoteMessages.tokenLine, "service" to service, "value" to value)
                return@run texts.reply(source, XVoteMessages.tokenFooter)
            }
            val existing = tokens[wanted] ?: tokens.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }?.value
            if (existing != null) return@run texts.reply(source, XVoteMessages.tokenOne, "service" to wanted, "value" to existing)
            if (wanted.length > 64 || wanted.any { !(it.isLetterOrDigit() || it in " ._:-") }) return@run texts.reply(source, XVoteMessages.tokenBadName)
            val made = receiver.newToken()
            val failed = receiver.saveToken(wanted, made)
            if (failed != null) texts.reply(source, XVoteMessages.tokenSaveFailed, "reason" to failed) else texts.reply(source, XVoteMessages.tokenMade, "service" to wanted, "value" to made)
        }
    }

    // ---- /xvote import ----

    fun importFrom(source: CommandSource, from: String, again: Boolean) {
        val imports = plugin.imports ?: return
        if (locked(this) { importing.also { if (!it) importing = true } }) return texts.reply(source, XVoteMessages.importRunning)
        plugin.platform.scheduler.run(Where.Async) { _ ->
            try {
                if (from == "nuvotifier") nuVotifier(source, imports) else totals(source, imports, from, again)
            } finally {
                importing = false
            }
        }
    }

    private fun nuVotifier(source: CommandSource, imports: VoteImports) {
        when (val read = imports.nuVotifier()) {
            null -> texts.reply(source, XVoteMessages.importNoNuVotifier)
            is Outcome.Err -> texts.reply(source, XVoteMessages.importNuVotifierFailed, "reason" to read.error)
            is Outcome.Ok -> {
                texts.reply(source, XVoteMessages.importNuVotifier, "folder" to "plugins/Votifier", "what" to read.value)
                // Reads the keys and tokens again, and moves to NuVotifier's port if it differs.
                plugin.platform.scheduler.run(Where.Global) { _ -> plugin.reloadFiles() }
            }
        }
    }

    private fun totals(source: CommandSource, imports: VoteImports, from: String, again: Boolean) {
        val read = imports.totals(from)
        if (read is Outcome.Err) return texts.reply(source, XVoteMessages.importFailed, "source" to from, "reason" to read.error)
        val (where, players) = (read as Outcome.Ok).value
        val votes = plugin.votes() ?: return texts.reply(source, XVoteMessages.importStoreFailed, "reason" to (plugin.failure ?: "the database isn't open"))
        val db = votes.backend.database
        val now = plugin.now()
        val periods = plugin.voters.periods
        val label = if (from == "superbvote") "SuperbVote" else "VotingPlugin"
        db.transaction { session ->
            Tx.run(session, db.sql) { tx ->
                val before = Ledger.imported(tx, from)
                if (before > 0 && !again) -before else Ledger.addImported(tx, from, players, periods, now).toLong()
            }
        }.onSettled { result, error ->
            when {
                result !is Outcome.Ok -> texts.reply(source, XVoteMessages.importStoreFailed, "reason" to ((result as? Outcome.Err)?.error?.detail ?: error.toString()))
                result.value < 0 -> texts.reply(source, XVoteMessages.importAgain, "source" to label, "ago" to Durations.format((now + result.value).milliseconds), "command" to from)
                else -> plugin.loadVoters().onSettled { _, _ -> texts.reply(source, XVoteMessages.importDone, "count" to result.value, "from" to where) }
            }
        }
    }

    // ---- /xvote party [start] ----

    fun party(source: CommandSource, start: Boolean) {
        val config = plugin.config
        if (!start) {
            return if (config[XVoteConfig.party.enabled]) {
                texts.reply(source, XVoteMessages.partyStatus, "votes" to (plugin.party.votes), "needed" to config[XVoteConfig.party.votes])
            } else {
                texts.reply(source, XVoteMessages.partyOff)
            }
        }
        val votes = plugin.votes() ?: return texts.reply(source, XVoteMessages.partyFailed, "reason" to (plugin.failure ?: "the database isn't open"))
        votes.startParty().onSettled { done, error ->
            if (done is Outcome.Ok) texts.reply(source, XVoteMessages.partyForced)
            else texts.reply(source, XVoteMessages.partyFailed, "reason" to ((done as? Outcome.Err)?.error?.detail ?: error.toString()))
        }
    }

    companion object {
        const val PER_PAGE = 10

        fun of(plugin: XVotePlugin): Actions = plugin.madeActions ?: locked(plugin) { plugin.madeActions ?: Actions(plugin).also { plugin.madeActions = it } }
    }
}
