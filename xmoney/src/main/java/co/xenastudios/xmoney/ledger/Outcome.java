package co.xenastudios.xmoney.ledger;

import co.xenastudios.xmoney.api.TransactionResult.Status;

/**
 * How a change ended.
 *
 * @param amount the units moved, or 0 when nothing did
 * @param balance the account's balance after, in units (the payer's, for a transfer)
 */
public record Outcome(Status status, long amount, long balance) {

    public boolean success() {
        return status == Status.SUCCESS;
    }

    static Outcome failed(Status status, long balance) {
        return new Outcome(status, 0, balance);
    }
}
