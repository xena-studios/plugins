package co.xenastudios.xmoney.api.event;

import co.xenastudios.xmoney.api.TransactionKind;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A balance changed, and the change is saved in the database. A transfer fires one for
 * each player.
 *
 * <p>Fired off the server's threads, in the order changes were saved, so a handler that
 * touches a player or the world hops to the right thread itself. Not fired for imports.
 * Only built when something listens for it.
 *
 * @since 1.0.0
 */
public final class BalanceChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final BigDecimal change;
    private final BigDecimal balance;
    private final TransactionKind kind;
    private final String source;
    private final String reason;
    private final UUID other;

    /**
     * Made by xMoney.
     *
     * @param player whose balance changed
     * @param change how much it changed by: negative when money left it
     * @param balance the balance just after
     * @param kind what moved it
     * @param source who or what moved it: a plugin's name, a player's or "Console"
     * @param reason the few words given with it, or empty
     * @param other the other player in a payment or transfer, or null
     * @since 1.0.0
     */
    public BalanceChangedEvent(UUID player, BigDecimal change, BigDecimal balance, TransactionKind kind, String source,
                               String reason, UUID other) {
        super(true);
        this.player = player;
        this.change = change;
        this.balance = balance;
        this.kind = kind;
        this.source = source;
        this.reason = reason;
        this.other = other;
    }

    /**
     * Whose balance changed.
     *
     * @return the player's uuid
     * @since 1.0.0
     */
    public UUID player() {
        return player;
    }

    /**
     * How much it changed by: negative when money left it.
     *
     * @return the change, with the currency's decimals
     * @since 1.0.0
     */
    public BigDecimal change() {
        return change;
    }

    /**
     * The balance just after this change. Another change may have followed already.
     *
     * @return the balance
     * @since 1.0.0
     */
    public BigDecimal balance() {
        return balance;
    }

    /**
     * What moved the money.
     *
     * @return the kind
     * @since 1.0.0
     */
    public TransactionKind kind() {
        return kind;
    }

    /**
     * Who or what moved it: the plugin's name for {@link TransactionKind#PLUGIN}, the
     * player's or staff member's name for commands, or "Console".
     *
     * @return the source
     * @since 1.0.0
     */
    public String source() {
        return source;
    }

    /**
     * The few words given with it, or empty.
     *
     * @return the reason
     * @since 1.0.0
     */
    public String reason() {
        return reason;
    }

    /**
     * The player on the other side of a payment or transfer.
     *
     * @return their uuid, or null when there's no other player
     * @since 1.0.0
     */
    public UUID other() {
        return other;
    }

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handlers
     * @since 1.0.0
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handlers
     * @since 1.0.0
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
