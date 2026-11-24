package co.xenastudios.m0.api

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * A Kotlin API written the way ROADMAP.md says (`@JvmStatic`, `@JvmOverloads`, futures for I/O,
 * no value classes or `suspend` in signatures), for the Java project next door to call.
 */
interface Wallet {

    /** Balance in ten-thousandths, from memory. */
    fun balance(player: UUID): Long

    /**
     * Pays [amount] in. `@JvmOverloads` isn't allowed on interface methods, so the default
     * argument is spelled out as a second, default method for Java.
     */
    fun deposit(player: UUID, amount: Long, reason: String): CompletableFuture<Transaction>

    fun deposit(player: UUID, amount: Long): CompletableFuture<Transaction> = deposit(player, amount, "api")

    /** Fired after every change, on our own bus (and as a Bukkit event on Bukkit). */
    fun onChange(listener: Consumer<BalanceChanged>): Subscription

    /** The richest [limit] accounts, read-only. */
    fun top(limit: Int = 10): List<Account>

    companion object {
        @Volatile
        private var current: Wallet? = null

        /** `Wallet.api()` from Java, thanks to `@JvmStatic`. */
        @JvmStatic
        fun api(): Wallet = current ?: throw IllegalStateException("the wallet isn't loaded yet")

        /** Called by the plugin. */
        @JvmStatic
        fun register(wallet: Wallet) {
            current = wallet
        }
    }
}

/** What a change did. A data class: Java gets getters, equals/hashCode/toString, componentN and copy. */
data class Transaction(val player: UUID, val before: Long, val after: Long, val reason: String)

/** An event. `val isPlayer` becomes `isPlayer()` in Java, `val name` becomes `getName()`. */
class BalanceChanged(val player: UUID, val before: Long, val after: Long, val isPlayer: Boolean) {
    val difference: Long get() = after - before
}

data class Account(val player: UUID, val name: String?, val balance: Long)

fun interface Subscription {
    fun cancel()
}

/** Results Java 17 can test with instanceof patterns (and switch on with Java 21). */
sealed interface Outcome {
    data object Done : Outcome
    data class Refused(val reason: String) : Outcome
}

/** A transfer with optional parts: `@JvmOverloads` on a constructor does work. */
class Transfer @JvmOverloads constructor(
    val from: UUID,
    val to: UUID,
    val amount: Long,
    val reason: String = "transfer",
    val notify: Boolean = true,
)

// ---- the pitfalls, kept here so `javap` shows what Java sees ----

/** A value class: any function taking it is name-mangled (`pay-<hash>`) and can't be called from Java. */
@JvmInline
value class Units(val raw: Long)

object Pitfalls {
    /** Java sees `pay-XXXX(UUID, long)`, which javac can't name. */
    fun pay(player: UUID, units: Units): Long = units.raw

    /** Without `@JvmStatic`: `Pitfalls.INSTANCE.plain()` from Java. */
    fun plain(): String = "plain"

    /** A Kotlin function type: from Java, the lambda has to `return Unit.INSTANCE;`. */
    fun listen(listener: (BalanceChanged) -> Unit) {
        listener(BalanceChanged(UUID(0, 0), 0, 1, true))
    }

    /** No `@Throws`: Java can't write `catch (java.io.IOException e)` around it. */
    fun read(): String = throw java.io.IOException("not there")

    /** A default argument without `@JvmOverloads`: Java must pass both. */
    fun greet(name: String, greeting: String = "Hello") = "$greeting, $name"

    /** `suspend`: Java sees an extra `Continuation` parameter and an `Object` return. */
    suspend fun later(): Long = 1
}
