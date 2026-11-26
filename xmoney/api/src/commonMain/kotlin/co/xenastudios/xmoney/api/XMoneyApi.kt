package co.xenastudios.xmoney.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xMoney's developer API, the same on Paper, Folia, Spigot and Fabric: balances and the top
 * list from memory, payments the ledger records under the plugin that made them, a player's
 * history, the currency's format, the economy's events and add-on hooks.
 *
 * Vault, VaultUnlocked (Bukkit) and the Common Economy API (Fabric) stay the universal way in,
 * and xMoney answers them all. Use this API for what they can't say: why money moved
 * (`/eco history` shows "xVote: vote reward"), a result that says why a payment was refused, the
 * top list, and a say over every payment ([registerHook]).
 *
 * Get it with [XMoney.api]. On Bukkit it's also in the services manager while xMoney is enabled.
 * Players are by [PlayerRef] (a UUID): from Java, `PlayerRefs.of(player.getUniqueId())`.
 *
 * Every method says how it runs: from memory at once on any thread, or a [Pending] (a
 * `CompletableFuture` on the JVM). A payment's is already complete on a single server, where
 * every balance is in memory; on a network sharing one database a withdrawal completes once the
 * database has agreed, off the calling thread. Nothing here reads the database on the calling
 * thread.
 *
 * @since 1.0.0
 */
public interface XMoneyApi {
    /** The running xMoney's version, such as `2.0.0`. */
    public val version: String

    /**
     * The economy's events: [co.xenastudios.xmoney.api.event.PreTransactionEvent] (cancellable)
     * and [co.xenastudios.xmoney.api.event.BalanceChangedEvent]. On Bukkit each is also a Bukkit
     * event in `co.xenastudios.xmoney.api.bukkit`.
     */
    public val events: EventBus

    /** A player's balance; zero without an account. From memory. */
    public fun balance(player: PlayerRef): Amount

    /** Whether a player has an account: they've joined, or been paid. From memory. */
    public fun hasAccount(player: PlayerRef): Boolean

    /** Whether a player has at least [amount]. From memory. */
    public fun has(player: PlayerRef, amount: Amount): Boolean

    /**
     * Pays [player] [amount], recorded as from [source] (your plugin's name) with [reason] (a few
     * words, up to 100 characters, such as `vote reward`). An account is made if they have none.
     * Fires [co.xenastudios.xmoney.api.event.PreTransactionEvent] first, on the calling thread,
     * and asks every [TransactionHook].
     */
    public fun deposit(source: String, player: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult>

    /**
     * Takes [amount] from [player] if they have it. On a network, completes once the shared
     * database has taken it, so two servers never spend the same money; if the database is
     * away, or doesn't take it up within five seconds, it's
     * [TransactionResult.Status.UNAVAILABLE] and nothing is taken, then or later.
     */
    public fun withdraw(source: String, player: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult>

    /**
     * Moves [amount] from [from] to [to] in one step: both change or neither does. The result's
     * balance is the payer's.
     */
    public fun transfer(source: String, from: PlayerRef, to: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult>

    /**
     * [count] (at most 100) of the richest players from rank [from] (1 is the richest). Accounts
     * that aren't players' (a town's bank, made by name through Vault) aren't listed. From memory.
     */
    public fun top(from: Int, count: Int): List<TopEntry>

    /** One page of [player]'s history, newest first, ten to a page. Reads the database, off the server's threads. */
    public fun history(player: PlayerRef, page: Int): Pending<HistoryPage>

    /** [amount] as xMoney shows it, such as `$1,234.50`, with no formatting codes. */
    public fun format(amount: Amount): String

    /** The currency, as the config sets it now. */
    public val currency: Currency

    /**
     * Asks [hook] before every change to a balance, after the pre-event, in the order hooks were
     * registered; the first refusal wins. [owner] names it in the console (your plugin's name).
     * Registering the same hook again does nothing.
     */
    public fun registerHook(owner: String, hook: TransactionHook)

    /** Stops asking [hook]. */
    public fun unregisterHook(hook: TransactionHook)
}

/**
 * Where xMoney's API is found, on every platform:
 *
 * ```java
 * XMoneyApi api = XMoney.api();
 * ```
 *
 * @since 2.0.0
 */
public object XMoney {
    @Volatile
    private var running: XMoneyApi? = null

    /**
     * xMoney's API.
     *
     * @throws IllegalStateException if xMoney isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XMoneyApi =
        running ?: throw IllegalStateException("xMoney isn't enabled. Add it to depend (or softdepend and check XMoney.isEnabled() first).")

    /** Whether xMoney is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xMoney as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XMoneyApi?) {
        running = api
    }
}
