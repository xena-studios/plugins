package co.xenastudios.xmoney.api;

/**
 * The currency xMoney uses, as its config sets it. 1.0 has one; more come with 1.1.
 *
 * @param id the currency's id, {@code "default"} in 1.0
 * @param singular its name for one, such as "dollar"
 * @param plural its name for more than one, such as "dollars"
 * @param symbol its symbol, such as "$"; may be empty
 * @param decimals how many decimal places amounts have, from 0 to 4
 * @since 1.0.0
 */
public record Currency(String id, String singular, String plural, String symbol, int decimals) {}
