package co.xenastudios.xmoney.imports;

import java.util.UUID;

/**
 * One balance another plugin has.
 *
 * @param name the name it knows the account by, or null
 * @param player false for an account that isn't a player's, such as a town's bank
 * @param balance the balance as it wrote it: a decimal number, not yet checked
 */
public record Found(UUID id, String name, boolean player, String balance) {}
