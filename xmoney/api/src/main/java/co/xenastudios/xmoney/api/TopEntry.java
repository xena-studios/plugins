package co.xenastudios.xmoney.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One place in the list of the richest players.
 *
 * @param rank the place, from 1
 * @param player the player's uuid
 * @param name the player's last known name
 * @param balance their balance
 * @since 1.0.0
 */
public record TopEntry(int rank, UUID player, String name, BigDecimal balance) {}
