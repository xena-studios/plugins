package co.xenastudios.xmoney.api;

/**
 * What moved money, as the ledger records it.
 *
 * @since 1.0.0
 */
public enum TransactionKind {
    /** The starting balance a new player gets. */
    START,
    /** A player paid another with {@code /pay}. */
    PAY,
    /** Staff gave money with {@code /eco give}. */
    GIVE,
    /** Staff took money with {@code /eco take}. */
    TAKE,
    /** Staff set a balance with {@code /eco set}, or reset it with {@code /eco reset}. */
    SET,
    /** A plugin, through Vault, VaultUnlocked or this API. */
    PLUGIN,
    /** An import from another economy plugin. */
    IMPORT
}
