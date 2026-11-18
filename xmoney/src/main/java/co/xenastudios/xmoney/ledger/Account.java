package co.xenastudios.xmoney.ledger;

import java.util.UUID;

/**
 * One account's balance in memory. Reads are plain volatile reads from any thread; every
 * change happens under the account's own lock (see {@link Ledger}), through
 * {@link Accounts#set}, so the top list and the total move with it.
 */
public final class Account {

    private final UUID id;
    private final boolean player;
    private volatile String name;
    volatile long balance;
    /**
     * On a network: what this server has added in memory and not yet written. Guarded by
     * the account's lock.
     */
    long pending;

    Account(UUID id, String name, boolean player, long balance) {
        this.id = id;
        this.name = name;
        this.player = player;
        this.balance = balance;
    }

    public UUID id() {
        return id;
    }

    /**
     * The last name known, to show: a player's, or the name a plugin made a non-player account
     * under. An account with none (its name went to another player) shows the start of its uuid.
     */
    public String name() {
        String known = name;
        return known == null || known.isEmpty() ? id.toString().substring(0, 8) : known;
    }

    /** The name as stored, which finds the account: empty if it has none. */
    String storedName() {
        String known = name;
        return known == null ? "" : known;
    }

    void name(String name) {
        this.name = name;
    }

    /** False for an account a plugin made by name, such as a town's bank. Those aren't in the top list. */
    public boolean player() {
        return player;
    }

    /** The balance in units. Any thread. */
    public long balance() {
        return balance;
    }
}
