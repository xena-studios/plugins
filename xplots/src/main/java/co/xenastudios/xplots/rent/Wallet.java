package co.xenastudios.xplots.rent;

import org.bukkit.entity.Player;

/** What rent is paid with: the server's economy, or an item. */
public interface Wallet {

    /** Whether payments can be taken now. Vault with no economy plugin can't. */
    boolean ready();

    boolean has(Player player, double amount);

    /** Takes the amount, or nothing if the player hasn't got it. */
    boolean take(Player player, double amount);

    /** Gives the amount back, as for a refund. */
    void give(Player player, double amount);

    /** The amount as players see it: "$128.00", "128 Coins". */
    String format(double amount);
}
