package co.xenastudios.xmoney.api

import co.xenastudios.core.api.PlayerRef

/**
 * What moved money, as the ledger records it and `/eco history` shows it.
 *
 * @since 1.0.0
 */
public enum class TransactionKind {
    /** A new player's starting balance. */
    START,

    /** One player paying another with `/pay`. */
    PAY,

    /** Staff adding money with `/eco give`. */
    GIVE,

    /** Staff taking money with `/eco take`. */
    TAKE,

    /** Staff setting or resetting a balance with `/eco set` or `/eco reset`. */
    SET,

    /** Another plugin, through Vault, VaultUnlocked, the Common Economy API or this API. */
    PLUGIN,

    /** An import from another economy plugin, with `/xmoney import`. */
    IMPORT,
}

/**
 * How a payment ended: its [status], how much really moved ([amount], zero when nothing did)
 * and the balance after ([balance]: the payer's, for a transfer).
 *
 * @since 1.0.0
 */
public class TransactionResult(
    public val status: Status,
    public val amount: Amount,
    public val balance: Amount,
) {
    /** Whether it went through. */
    public val isSuccess: Boolean get() = status == Status.SUCCESS

    override fun equals(other: Any?): Boolean =
        other is TransactionResult && other.status == status && other.amount == amount && other.balance == balance

    override fun hashCode(): Int = (status.hashCode() * 31 + amount.hashCode()) * 31 + balance.hashCode()

    override fun toString(): String = "TransactionResult($status, $amount, balance $balance)"

    /** Why a payment did or didn't go through. */
    public enum class Status {
        /** It went through. */
        SUCCESS,

        /** The payer hasn't got that much. */
        INSUFFICIENT_FUNDS,

        /** It would take a balance past `max-balance`. */
        BALANCE_LIMIT,

        /** Not an amount it can move: zero, negative, or past what an account holds. */
        INVALID_AMOUNT,

        /** A transfer from an account to itself. */
        SAME_ACCOUNT,

        /** A [co.xenastudios.xmoney.api.event.PreTransactionEvent] listener or a [TransactionHook] refused it. */
        CANCELLED,

        /**
         * On a network, the shared database didn't take it up in time, or is away: nothing was
         * taken, then or later.
         */
        UNAVAILABLE,
    }
}

/**
 * The currency, as the config sets it now.
 *
 * @since 1.0.0
 */
public class Currency(
    /** Its id, `default`: one currency in 2.0, the column is there for more. */
    public val id: String,
    public val singular: String,
    public val plural: String,
    public val symbol: String,
    /** Places amounts are shown with, 0 to 4. Amounts themselves always keep four. */
    public val decimals: Int,
) {
    override fun toString(): String = "Currency($id, $singular/$plural, $symbol, $decimals decimals)"
}

/**
 * One place in the list of the richest players.
 *
 * @since 1.0.0
 */
public class TopEntry(
    /** From 1, the richest. */
    public val rank: Int,
    public val player: PlayerRef,
    /** Their last known name. */
    public val name: String,
    public val balance: Amount,
) {
    override fun toString(): String = "#$rank $name $balance"
}

/**
 * One change to a balance, from the ledger.
 *
 * @since 1.0.0
 */
public class HistoryEntry(
    /** The ledger row's id: later changes have higher ones. */
    public val id: Long,
    /** When, in milliseconds since 1970. */
    public val timeMillis: Long,
    /** How much: negative when money left. */
    public val amount: Amount,
    /** The balance after. */
    public val balance: Amount,
    public val kind: TransactionKind,
    /** Who or what moved it: a plugin's name, a player's, or `Console`. */
    public val source: String,
    /** The few words given with it, or empty. */
    public val reason: String,
    /** The other player in a payment, or `null`. */
    public val other: PlayerRef?,
)

/**
 * A page of a player's history, newest first.
 *
 * @since 1.0.0
 */
public class HistoryPage(
    public val entries: List<HistoryEntry>,
    /** From 1. */
    public val page: Int,
    public val pages: Int,
    /** How many changes the player has in all. */
    public val total: Long,
)

/**
 * An add-on's say over payments: asked before every change to a balance (not imports), on the
 * thread making it, after the [co.xenastudios.xmoney.api.event.PreTransactionEvent]. A hook
 * can refuse one and say why, which `/pay` and `/eco` show the player: a plot plugin's frozen
 * account, a market's trade lock, a limit on what new players may send. Register one with
 * [XMoneyApi.registerHook].
 *
 * It's asked on a payment's hot path, perhaps on a shop's async thread: answer from memory,
 * never from a database. One that throws is logged and removed.
 *
 * @since 2.0.0
 */
public fun interface TransactionHook {
    /**
     * `null` to let [transaction] through; otherwise why not, in xMoney's markup, and the
     * payment is refused as [TransactionResult.Status.CANCELLED].
     */
    public fun refuse(transaction: co.xenastudios.xmoney.api.event.PreTransactionEvent): String?
}
