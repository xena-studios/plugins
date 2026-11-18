package co.xenastudios.xmoney.ledger;

import java.util.UUID;

/**
 * One change to one account, as a ledger row.
 *
 * @param delta how much the balance changed, in units: negative when money left it
 * @param after the balance just after, in units. On a network the database's, filled in by the writer.
 * @param other the other player in a payment, or null
 */
public record Entry(Account account, long delta, long after, Cause cause, UUID other, long time) {}
