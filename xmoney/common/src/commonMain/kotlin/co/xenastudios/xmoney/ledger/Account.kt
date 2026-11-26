package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import kotlin.concurrent.Volatile

/**
 * One account's balance in memory. Reads are plain volatile reads from any thread; every change
 * happens under the account's own lock ([co.xenastudios.core.common.locked] on it, see
 * [Ledger]), through [Accounts.set], so the top list and the total move with it.
 */
public class Account internal constructor(
    public val id: PlayerRef,
    name: String,
    /** False for an account a plugin made by name, such as a town's bank: those aren't in the top list. */
    public val player: Boolean,
    balance: Long,
) {
    @Volatile
    internal var storedName: String = name

    /** The balance in units. Any thread. */
    @Volatile
    public var balance: Long = balance
        internal set

    /** On a network: what this server has added in memory and not yet saved. Under the account's lock. */
    internal var pending: Long = 0

    /** Whether it was forgotten (`/xmoney forget`): a change that finds it is refused. */
    @Volatile
    internal var gone: Boolean = false

    /**
     * The last name known, to show: a player's, or the name a plugin made a non-player account
     * under. One with none (its name went to another player) shows the start of its uuid.
     */
    public val name: String get() = storedName.ifEmpty { id.toString().substring(0, 8) }

    override fun toString(): String = "Account($name, $balance)"
}

/** Why money moved, as the ledger records it. [source] and [reason] are cut to the columns' lengths. */
public class Cause(public val kind: co.xenastudios.xmoney.api.TransactionKind, source: String, reason: String) {
    public val source: String = clip(source.ifBlank { "Unknown" }, Tables.SOURCE_LENGTH)
    public val reason: String = clip(reason, Tables.REASON_LENGTH)

    private fun clip(text: String, length: Int) = if (text.length <= length) text else text.substring(0, length)
}

/** How a change ended: what moved ([amount], 0 when nothing did) and the balance after ([balance]: the payer's, for a transfer). Units. */
public class Outcome(public val status: co.xenastudios.xmoney.api.TransactionResult.Status, public val amount: Long, public val balance: Long) {
    public val success: Boolean get() = status == co.xenastudios.xmoney.api.TransactionResult.Status.SUCCESS

    override fun toString(): String = "Outcome($status, $amount, $balance)"

    public companion object {
        internal fun failed(status: co.xenastudios.xmoney.api.TransactionResult.Status, balance: Long) = Outcome(status, 0, balance)
    }
}

/** One change to one account, as a ledger row: by [delta], [after] being the balance it left. */
internal class Entry(
    val account: Account,
    val delta: Long,
    var after: Long,
    val cause: Cause,
    val other: PlayerRef?,
    val time: Long,
)
