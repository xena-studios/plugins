package co.xenastudios.xmoney.bukkit.vault

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xmoney.XMoneyPlugin
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.Amounts
import co.xenastudios.xmoney.api.Rounding
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult.Status
import co.xenastudios.xmoney.bukkit.BukkitMoneyServer
import co.xenastudios.xmoney.ledger.Account
import co.xenastudios.xmoney.ledger.Cause
import co.xenastudios.xmoney.ledger.Ledger
import co.xenastudios.xmoney.ledger.Outcome
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin

/**
 * What both Vault economies share: finding accounts without blocking, turning callers' amounts
 * into units, the ledger's cause, and waiting for an answer Vault needs on the spot. Every method
 * answers from memory, from any thread; on a network a withdrawal waits for the shared database,
 * a server thread at most `network-wait-ms` for it to be taken up.
 */
internal class VaultBridge(val plugin: Plugin, val xmoney: XMoneyPlugin, private val server: BukkitMoneyServer) {
    // Made with the first payment that doesn't say which plugin it's from.
    private var callers: Callers? = null
    private val lastWarned = AtomicLong()
    private val refusedSince = AtomicLong()

    /**
     * The ledger, once the balances are read. A Vault call in the moments while the server starts,
     * before they are, waits for them (they're read on a thread of their own as the plugin enables,
     * so a call can only come early from a plugin enabling right after): never longer than [READY_WAIT_S].
     */
    val ledger: Ledger?
        get() {
            xmoney.ledger?.let { return it }
            if (xmoney.failure != null) return null
            return try {
                xmoney.ready.get(READY_WAIT_S, TimeUnit.SECONDS)
                xmoney.ledger
            } catch (e: Exception) {
                null
            }
        }

    fun cause(pluginName: String?): Cause =
        Cause(TransactionKind.PLUGIN, if (pluginName.isNullOrBlank()) (callers ?: Callers(plugin).also { callers = it }).plugin() else pluginName, "")

    fun existing(player: OfflinePlayer?): Account? = player?.let { ledger?.accounts?.get(PlayerRef.of(it.uniqueId.toKotlin())) }

    fun existing(id: UUID?): Account? = id?.let { ledger?.accounts?.get(PlayerRef.of(it.toKotlin())) }

    /** The account by this name, or failing that, the account of a player the server has cached by it. */
    fun existing(name: String?): Account? {
        if (name.isNullOrBlank()) return null
        val ledger = ledger ?: return null
        ledger.accounts.named(name)?.let { return it }
        val cached = server.cachedId(name) ?: return null
        return ledger.accounts[cached]
    }

    /** The account to pay, made empty if there's none. Never asks the disk or the web for a name. */
    fun payee(id: UUID): Account? {
        val ledger = ledger ?: return null
        return xmoney.payee(ledger, PlayerRef.of(id.toKotlin()))
    }

    fun payee(player: OfflinePlayer?): Account? = player?.let { payee(it.uniqueId) }

    /**
     * A name-based account: the player or account by that name; a player the server knows by it
     * (from its caches, never the web); or otherwise a non-player account made for it. `null` if
     * more than one account has had the name and the server's caches don't say whose it is now.
     */
    fun payee(name: String?): Account? {
        if (name.isNullOrBlank()) return null
        val ledger = ledger ?: return null
        ledger.accounts.named(name)?.let { return it }
        server.cachedId(name)?.let { return xmoney.payee(ledger, it) }
        return if (ledger.accounts.ambiguous(name)) null else ledger.named(name)
    }

    fun balance(account: Account?): Long = account?.balance ?: 0

    /** Whether the account has the amount, rounded up as a withdrawal would be. NaN or negative: only "has nothing" is true. */
    fun has(account: Account?, amount: Double): Boolean {
        if (amount.isNaN()) return false
        if (amount <= 0) return true
        val units = outgoing(amount)
        return units >= 0 && balance(account) >= units
    }

    fun has(account: Account?, amount: BigDecimal?): Boolean {
        if (amount == null) return false
        if (amount.signum() <= 0) return true
        val units = outgoing(amount)
        return units >= 0 && balance(account) >= units
    }

    /** An amount to pay in or set, in units, rounded down past four places: -1 if it can't be one (NaN, infinite, negative, or past the most an account holds). */
    fun incoming(amount: Double): Long = Amount.ofDouble(amount, Rounding.DOWN)?.units?.takeIf { it >= 0 } ?: -1

    fun incoming(amount: BigDecimal?): Long = amount?.let { Amounts.of(it, Rounding.DOWN) }?.units?.takeIf { it >= 0 } ?: -1

    /** An amount to take, in units, rounded up past four places, so a positive amount is never 0; -1 if it can't be one. */
    fun outgoing(amount: Double): Long = Amount.ofDouble(amount, Rounding.UP)?.units?.takeIf { it >= 0 } ?: -1

    fun outgoing(amount: BigDecimal?): Long = amount?.let { Amounts.of(it, Rounding.UP) }?.units?.takeIf { it >= 0 } ?: -1

    /** An amount to show, rounded half up to the currency's decimals; -1 if it can't be one. */
    fun shown(amount: Double): Long = Amount.ofDouble(amount, Rounding.HALF_UP)?.units?.let { XMoneyPlugin.roundTo(it, decimals) } ?: -1

    fun shown(amount: BigDecimal): Long = Amounts.of(amount, Rounding.HALF_UP)?.units?.let { XMoneyPlugin.roundTo(it, decimals) } ?: -1

    val decimals: Int get() = xmoney.compiled.format.decimals

    val max: Long get() = xmoney.compiled.max

    fun format(units: Long): String = xmoney.format(units)

    /** Every place a balance has, and at least the currency's decimals: `12.50`, `0.995`. */
    fun decimal(units: Long): BigDecimal = Amounts.toBigDecimal(Amount.ofUnits(units), decimals)

    fun withdraw(account: Account, units: Long, cause: Cause): Outcome = await(ledger!!.withdraw(account, units, cause), account)

    fun deposit(account: Account, units: Long, cause: Cause): Outcome = await(ledger!!.deposit(account, units, cause), account)

    fun set(account: Account, units: Long, cause: Cause): Outcome = await(ledger!!.set(account, units, cause), account)

    fun transfer(from: Account, to: Account, units: Long, cause: Cause): Outcome = await(ledger!!.transfer(from, to, units, cause), from)

    /**
     * The outcome now, for Vault's callers, which need an answer on the spot. Only a change the
     * shared database decides (on a network) isn't done already. Not taken up by the writer within
     * `network-wait-ms` on a server thread, or five seconds elsewhere, it's abandoned and refused,
     * and the writer never runs it, so nothing was taken. Once started, its real outcome is waited
     * for: whether it went through is never guessed.
     */
    fun await(result: Pending<Outcome>, account: Account): Outcome {
        if (result.isDone) return result.join()
        val refused = Outcome(Status.UNAVAILABLE, 0, account.balance)
        // On the database's own writer (a listener of ours, called back there), waiting would wait for itself.
        if (Thread.currentThread().name.endsWith("database writer")) {
            ledger?.abandon(result, refused)
            return if (result.isDone) result.join() else refused
        }
        val wait = if (server.isServerThread()) xmoney.config[co.xenastudios.xmoney.config.XMoneyConfig.networkWaitMs].toLong().coerceIn(1, MAX_SERVER_THREAD_WAIT_MS)
        else Ledger.WAIT_MS
        return try {
            result.get(wait, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            if (ledger?.abandon(result, refused) == true) {
                warn(wait)
                refused
            } else {
                // Started: the database is deciding it.
                result.join()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (ledger?.abandon(result, refused) == true) refused else result.join()
        } catch (e: ExecutionException) {
            throw IllegalStateException(e.cause)
        }
    }

    // Once a minute at most, however many are refused: a shop clicked during an outage is many.
    private fun warn(waited: Long) {
        val refused = refusedSince.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastWarned.get()
        if (now - last >= 60_000 && lastWarned.compareAndSet(last, now)) {
            refusedSince.set(0)
            val problem = xmoney.ledger?.problem
            xmoney.log.warn("$refused ${if (refused == 1L) "payment was" else "payments were"} refused after waiting ${waited}ms for the shared database. " +
                "Nothing was taken from them. " + (if (problem == null) "It's slow to answer." else "The problem: $problem"))
        }
    }

    companion object {
        /** The most `network-wait-ms` may be: a server thread never waits longer to be refused. */
        const val MAX_SERVER_THREAD_WAIT_MS = 250L

        /** The longest a Vault call made while the server starts waits for the balances to be read. */
        const val READY_WAIT_S = 30L

        /** Why it didn't happen, for Vault's error message. */
        fun reason(outcome: Outcome): String = when (outcome.status) {
            Status.SUCCESS -> ""
            Status.INSUFFICIENT_FUNDS -> "Insufficient funds"
            Status.BALANCE_LIMIT -> "That would take the balance over the most it can hold"
            Status.INVALID_AMOUNT -> "Not a valid amount"
            Status.SAME_ACCOUNT -> "Can't transfer to the same account"
            Status.CANCELLED -> "Cancelled by another plugin"
            Status.UNAVAILABLE -> "The economy's database didn't answer in time"
        }
    }
}

/**
 * The [VaultBridge], made with the first Vault call rather than as the server starts: a server
 * where no plugin uses Vault never loads it (decisions/2026-11-25-a-lighter-core.md).
 */
internal class VaultHolder(private val plugin: Plugin, private val xmoney: XMoneyPlugin, private val server: BukkitMoneyServer) {
    @Volatile
    private var made: VaultBridge? = null

    fun get(): VaultBridge = made ?: synchronized(this) { made ?: VaultBridge(plugin, xmoney, server).also { made = it } }
}

/** Java's uuid as Kotlin's. */
internal fun UUID.toKotlin(): kotlin.uuid.Uuid = kotlin.uuid.Uuid.fromLongs(mostSignificantBits, leastSignificantBits)

/** Kotlin's uuid as Java's. */
internal fun PlayerRef.toJava(): UUID = uuid.toLongs { most, least -> UUID(most, least) }

/**
 * Which plugin called, for the ledger: Vault 1's calls don't say. The first class on the stack
 * from another plugin's class loader, found once per class and cached. Payments only.
 */
internal class Callers(private val self: Plugin) {
    private val known = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()
    // Made once; a JDK that refuses it (the security manager's class gone) names every caller "Vault".
    private val stack: Stack? = try {
        Stack()
    } catch (e: Throwable) {
        null
    }

    fun plugin(): String {
        val classes = try {
            stack?.classes() ?: return "Vault"
        } catch (e: Throwable) {
            return "Vault"
        }
        for (type in classes) {
            val name = known.getOrPut(type) {
                try {
                    val owner = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(type)
                    if (owner === self) "" else owner.name
                } catch (e: IllegalArgumentException) {
                    ""
                } catch (e: IllegalStateException) {
                    ""
                }
            }
            if (name.isNotEmpty()) return name
        }
        return "Vault"
    }

    // The classes on the stack, Java 8 to 25: SecurityManager's, which only reads them.
    @Suppress("DEPRECATION", "removal")
    private class Stack : SecurityManager() {
        fun classes(): Array<Class<*>> = classContext
    }
}
