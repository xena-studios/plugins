package co.xenastudios.xmoney

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.map
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xmoney.XMoneyPlugin.Companion.HISTORY_PAGE
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.HistoryEntry
import co.xenastudios.xmoney.api.HistoryPage
import co.xenastudios.xmoney.api.TopEntry
import co.xenastudios.xmoney.api.TransactionHook
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult
import co.xenastudios.xmoney.api.event.BalanceChangedEvent
import co.xenastudios.xmoney.api.event.PreTransactionEvent
import co.xenastudios.xmoney.ledger.Cause
import co.xenastudios.xmoney.ledger.Outcome
import co.xenastudios.xmoney.ledger.SavedChange
import kotlin.concurrent.Volatile

/**
 * The API's changes and reads that go past memory, add-ons' hooks with the pre-event, and the
 * post-events: [XMoneyPlugin]'s, made the first time one is needed.
 */
internal class Money(private val plugin: XMoneyPlugin) {
    /** The pre-event and add-ons' hooks. */
    val hooks = Hooks(plugin.log)

    /** The post-events, fired in order on an async task of their own, never the database's writer. */
    val postEvents = PostEvents()

    init {
        plugin.beforeFirstChange?.invoke()
    }

    // The API's result for an outcome.
    private fun result(outcome: Outcome) = TransactionResult(outcome.status, Amount.ofUnits(outcome.amount), Amount.ofUnits(outcome.balance))

    private fun unavailable(): Pending<TransactionResult> =
        pendingOf(TransactionResult(TransactionResult.Status.UNAVAILABLE, Amount.ZERO, Amount.ZERO))

    fun deposit(source: String, player: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult> {
        val ledger = plugin.ledger ?: return unavailable()
        return ledger.deposit(plugin.payee(ledger, player), amount.units, Cause(TransactionKind.PLUGIN, source, reason)).map(::result)
    }

    fun withdraw(source: String, player: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult> {
        val ledger = plugin.ledger ?: return unavailable()
        val account = ledger.accounts[player]
            ?: return pendingOf(TransactionResult(if (amount.units <= 0) TransactionResult.Status.INVALID_AMOUNT else TransactionResult.Status.INSUFFICIENT_FUNDS, Amount.ZERO, Amount.ZERO))
        return ledger.withdraw(account, amount.units, Cause(TransactionKind.PLUGIN, source, reason)).map(::result)
    }

    fun transfer(source: String, from: PlayerRef, to: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult> {
        val ledger = plugin.ledger ?: return unavailable()
        if (from == to) return pendingOf(TransactionResult(TransactionResult.Status.SAME_ACCOUNT, Amount.ZERO, plugin.balance(from)))
        val payer = ledger.accounts[from]
            ?: return pendingOf(TransactionResult(if (amount.units <= 0) TransactionResult.Status.INVALID_AMOUNT else TransactionResult.Status.INSUFFICIENT_FUNDS, Amount.ZERO, Amount.ZERO))
        return ledger.transfer(payer, plugin.payee(ledger, to), amount.units, Cause(TransactionKind.PLUGIN, source, reason)).map(::result)
    }

    fun top(from: Int, count: Int): List<TopEntry> {
        val ledger = plugin.ledger ?: return emptyList()
        return ledger.accounts.top(maxOf(1, from), count.coerceIn(0, 100)).map { TopEntry(it.rank, it.account.id, it.account.name, Amount.ofUnits(it.balance)) }
    }

    fun history(player: PlayerRef, page: Int): Pending<HistoryPage> {
        val ledger = plugin.ledger ?: return pendingOf(HistoryPage(emptyList(), 1, 1, 0))
        val done = newPending<HistoryPage>()
        ledger.history(player, 0, 0).onSettled { counted, _ ->
            val total = (counted as? DbOutcome.Ok)?.value?.first ?: 0
            val pages = maxOf(1, ((total + HISTORY_PAGE - 1) / HISTORY_PAGE).toInt())
            val number = page.coerceIn(1, pages)
            ledger.history(player, (number - 1).toLong() * HISTORY_PAGE, HISTORY_PAGE).onSettled { read, _ ->
                val rows = (read as? DbOutcome.Ok)?.value?.second.orEmpty()
                done.resolve(HistoryPage(rows.map {
                    HistoryEntry(it.id, it.time, Amount.ofUnits(it.amount), Amount.ofUnits(it.balance), it.kind, it.source, it.reason, it.other)
                }, number, pages, total))
            }
        }
        return done
    }



    inner class PostEvents {
        private val queue = ArrayList<SavedChange>()
        private var draining = false

        fun add(changes: List<SavedChange>) {
            if (!plugin.bus.hasListeners(BalanceChangedEvent.TYPE)) return
            val start = locked(queue) {
                queue += changes
                if (draining) false else {
                    draining = true
                    true
                }
            }
            if (start) plugin.platform.scheduler.run(Where.Async) { drain() }
        }

        fun drain() {
            while (true) {
                val batch = locked(queue) {
                    if (queue.isEmpty()) {
                        draining = false
                        return
                    }
                    queue.toList().also { queue.clear() }
                }
                for (change in batch) {
                    try {
                        plugin.bus.fire(
                            BalanceChangedEvent.TYPE,
                            BalanceChangedEvent(change.account, Amount.ofUnits(change.change), Amount.ofUnits(change.balance), change.cause.kind, change.cause.source, change.cause.reason, change.other),
                        )
                    } catch (e: Throwable) {
                        if (isFatal(e)) throw e
                        plugin.log.error("A BalanceChangedEvent listener failed", e)
                    }
                }
            }
        }

        fun flush() {
            locked(queue) { queue.clear() }
        }
    }

    /** The pre-event and add-ons' hooks, then the post-events. */
    inner class Hooks(private val log: RecentErrors) {
        @Volatile
        private var hooks: List<Pair<String, TransactionHook>> = emptyList()
        private val lock = Any()

        fun add(owner: String, hook: TransactionHook) = locked(lock) {
            if (hooks.none { it.second === hook }) hooks = hooks + (owner to hook)
        }

        fun remove(hook: TransactionHook) = locked(lock) { hooks = hooks.filter { it.second !== hook } }

        fun clear() = locked(lock) { hooks = emptyList() }

        fun refuse(account: PlayerRef, change: Long, cause: Cause, other: PlayerRef?): String? {
            val hooks = hooks
            if (hooks.isEmpty() && !plugin.bus.hasListeners(PreTransactionEvent.TYPE)) return null
            val event = PreTransactionEvent(account, Amount.ofUnits(change), cause.kind, cause.source, cause.reason, other)
            if (plugin.bus.hasListeners(PreTransactionEvent.TYPE) && plugin.bus.fire(PreTransactionEvent.TYPE, event).isCancelled) return ""
            for ((owner, hook) in hooks) {
                val reason = try {
                    hook.refuse(event)
                } catch (e: Throwable) {
                    if (isFatal(e)) throw e
                    log.error("$owner's TransactionHook failed, so it's removed", e)
                    remove(hook)
                    null
                }
                if (reason != null) return reason
            }
            return null
        }

    }
}
