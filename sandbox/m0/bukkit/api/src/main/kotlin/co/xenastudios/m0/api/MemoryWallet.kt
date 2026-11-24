package co.xenastudios.m0.api

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** The implementation a plugin would register, in Kotlin. */
class MemoryWallet : Wallet {
    private val balances = ConcurrentHashMap<UUID, Long>()
    private val listeners = CopyOnWriteArrayList<Consumer<BalanceChanged>>()

    override fun balance(player: UUID): Long = balances[player] ?: 0

    override fun deposit(player: UUID, amount: Long, reason: String): CompletableFuture<Transaction> =
        CompletableFuture.supplyAsync {
            require(amount > 0) { "amount must be positive" }
            val after = balances.merge(player, amount, Long::plus)!!
            val change = Transaction(player, after - amount, after, reason)
            listeners.forEach { it.accept(BalanceChanged(player, change.before, after, true)) }
            change
        }

    override fun onChange(listener: Consumer<BalanceChanged>): Subscription {
        listeners += listener
        return Subscription { listeners -= listener }
    }

    override fun top(limit: Int): List<Account> = balances.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { Account(it.key, null, it.value) }
}
