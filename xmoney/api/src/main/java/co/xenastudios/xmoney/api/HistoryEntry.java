package co.xenastudios.xmoney.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One change to a player's balance, from the ledger.
 *
 * @param id the ledger row's number, rising over time
 * @param time when it happened
 * @param amount how much the balance changed: negative when money left it
 * @param balance the balance just after
 * @param kind what moved it
 * @param source who or what moved it: a plugin's name, a player's or "Console"
 * @param reason the few words given with it, or empty
 * @param other the other player in a payment or transfer, or null
 * @since 1.0.0
 */
public record HistoryEntry(long id, Instant time, BigDecimal amount, BigDecimal balance, TransactionKind kind,
                           String source, String reason, UUID other) {}
