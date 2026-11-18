package co.xenastudios.xmoney.api;

import java.math.BigDecimal;

/**
 * How a payment ended.
 *
 * @param status whether it happened, and if not, why
 * @param amount how much moved: the amount asked for, to four decimal places (see
 *     {@link XMoneyApi}); zero when it didn't happen
 * @param balance the player's balance afterwards (the payer's, for a transfer)
 * @since 1.0.0
 */
public record TransactionResult(Status status, BigDecimal amount, BigDecimal balance) {

    /**
     * Whether the money moved.
     *
     * @return whether the status is {@link Status#SUCCESS}
     * @since 1.0.0
     */
    public boolean success() {
        return status == Status.SUCCESS;
    }

    /**
     * Whether a payment happened, and if not, why.
     *
     * @since 1.0.0
     */
    public enum Status {
        /** The money moved. */
        SUCCESS,
        /** The payer hasn't enough. */
        INSUFFICIENT_FUNDS,
        /** It would take the balance over the highest the config allows. */
        BALANCE_LIMIT,
        /** The amount isn't above zero (a deposit under a ten-thousandth is), or is too large to hold. */
        INVALID_AMOUNT,
        /** A transfer from a player to themselves. */
        SAME_ACCOUNT,
        /** A plugin cancelled the {@link co.xenastudios.xmoney.api.event.PreTransactionEvent}. */
        CANCELLED,
        /**
         * On a network: the shared database is failing, or didn't take the payment up in time.
         * Nothing moved, and it won't later.
         */
        UNAVAILABLE
    }
}
