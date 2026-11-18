package co.xenastudios.xmoney.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * xMoney's developer API: balances and the top list from memory, payments that the ledger
 * records under the plugin that made them, a player's history, and the currency's format.
 *
 * <p>Vault and VaultUnlocked stay the universal way in, and xMoney answers both. Use this
 * API for what they can't say: why money moved ({@code /eco history} shows "xVote: vote
 * reward" rather than an unnamed change), a result that says why a payment was refused, and
 * the top list.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered while
 * xMoney is enabled.
 *
 * <p>Every method says how it runs: either it may be called from any thread, answers from
 * memory and returns immediately, or it returns a {@link CompletableFuture}. A payment's
 * future is already complete on a single server, where every balance is in memory; on a
 * network sharing one database, a withdrawal completes once the database has agreed, off
 * the calling thread. Nothing here ever reads the database on the calling thread.
 *
 * <p>Amounts keep four decimal places, whatever the currency's
 * {@linkplain Currency#decimals() decimals} (those are for showing amounts), so a price split
 * in two costs what it would whole. Past four places, what's paid in is rounded down and what's
 * taken is rounded up, so rounding never makes money; the result says what was actually
 * moved. Balances come back with every place they have and at least the currency's decimals:
 * {@code 12.50}, or {@code 0.995}.
 *
 * @since 1.0.0
 */
public interface XMoneyApi {

    /**
     * xMoney's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xMoney isn't installed and enabled
     * @since 1.0.0
     */
    static XMoneyApi get() {
        XMoneyApi api = Bukkit.getServicesManager().load(XMoneyApi.class);
        if (api == null) {
            throw new IllegalStateException("xMoney isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * A player's balance. Zero for a player without an account.
     *
     * <p>Any thread, answers from memory, returns immediately.
     *
     * @param player the player's uuid
     * @return the balance, with every decimal place it has and at least the currency's
     * @since 1.0.0
     */
    BigDecimal balance(UUID player);

    /**
     * Whether a player has an account: they've joined, or been paid.
     *
     * <p>Any thread, answers from memory, returns immediately.
     *
     * @param player the player's uuid
     * @return whether there's an account
     * @since 1.0.0
     */
    boolean hasAccount(UUID player);

    /**
     * Whether a player has at least this much.
     *
     * <p>Any thread, answers from memory, returns immediately.
     *
     * @param player the player's uuid
     * @param amount how much, not negative; past four places rounded up, as a withdrawal would be
     * @return whether their balance covers it
     * @since 1.0.0
     */
    boolean has(UUID player, BigDecimal amount);

    /**
     * Pays a player, recorded in the ledger as from {@code source} with {@code reason}.
     * Fires {@link co.xenastudios.xmoney.api.event.PreTransactionEvent} first, on the
     * calling thread, and {@link co.xenastudios.xmoney.api.event.BalanceChangedEvent} once
     * it's saved.
     *
     * <p>Any thread. On a single server the future is already complete when it's returned.
     *
     * @param source the plugin paying
     * @param player who's paid; an account is made if they have none
     * @param amount how much, above zero; past four places rounded down
     * @param reason a few words for the ledger, such as "vote reward"; up to 100 characters
     * @return a future completed with the result
     * @since 1.0.0
     */
    CompletableFuture<TransactionResult> deposit(Plugin source, UUID player, BigDecimal amount, String reason);

    /**
     * Takes money from a player, if they have it, recorded in the ledger as for
     * {@code source} with {@code reason}. Fires the same events as {@link #deposit}.
     *
     * <p>Any thread. On a single server the future is already complete when it's returned;
     * on a network it completes off the calling thread once the shared database has taken
     * the money, so two servers can never spend the same money twice. If the database is
     * failing, or doesn't take it up within five seconds, it completes as
     * {@link TransactionResult.Status#UNAVAILABLE} and nothing is taken, then or later.
     *
     * @param source the plugin charging
     * @param player who pays
     * @param amount how much, above zero; past four places rounded up
     * @param reason a few words for the ledger, such as "teleport cost"; up to 100 characters
     * @return a future completed with the result: {@link TransactionResult.Status#INSUFFICIENT_FUNDS}
     *     when they haven't enough
     * @since 1.0.0
     */
    CompletableFuture<TransactionResult> withdraw(Plugin source, UUID player, BigDecimal amount, String reason);

    /**
     * Moves money from one player to another in one step: both change, or neither does.
     * Fires {@link co.xenastudios.xmoney.api.event.PreTransactionEvent} once, for the
     * payer, and {@link co.xenastudios.xmoney.api.event.BalanceChangedEvent} for each.
     *
     * <p>Any thread, and complete at once on a single server, as {@link #withdraw}.
     *
     * @param source the plugin moving it
     * @param from who pays
     * @param to who's paid; an account is made if they have none
     * @param amount how much, above zero; past four places rounded up
     * @param reason a few words for the ledger; up to 100 characters
     * @return a future completed with the result; its balance is the payer's
     * @since 1.0.0
     */
    CompletableFuture<TransactionResult> transfer(Plugin source, UUID from, UUID to, BigDecimal amount, String reason);

    /**
     * Part of the list of the richest players, highest first: {@code count} entries from
     * rank {@code from}. Accounts that aren't players (a town's bank, made by name through
     * Vault) aren't listed.
     *
     * <p>Any thread, answers from memory (a sorted list kept up to date on every change),
     * returns immediately.
     *
     * @param from the first rank wanted, from 1
     * @param count how many, at most 100
     * @return the entries, fewer when the list is shorter
     * @since 1.0.0
     */
    List<TopEntry> top(int from, int count);

    /**
     * One page of a player's history, newest first, ten to a page. Rows older than the
     * ledger keeps (a year by default) are gone.
     *
     * <p>Returns a future completed off the server's threads: it reads the database.
     *
     * @param player the player's uuid
     * @param page the page, from 1
     * @return a future completed with the page
     * @since 1.0.0
     */
    CompletableFuture<HistoryPage> history(UUID player, int page);

    /**
     * The amount as xMoney shows it, such as {@code $1,234.50}.
     *
     * <p>Any thread, returns immediately.
     *
     * @param amount the amount
     * @return the text, with no formatting codes
     * @since 1.0.0
     */
    String format(BigDecimal amount);

    /**
     * The currency: its names, symbol and decimals, as the config sets them now.
     *
     * <p>Any thread, returns immediately.
     *
     * @return the currency
     * @since 1.0.0
     */
    Currency currency();
}
