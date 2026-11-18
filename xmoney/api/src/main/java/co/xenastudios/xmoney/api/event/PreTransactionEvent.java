package co.xenastudios.xmoney.api.event;

import co.xenastudios.xmoney.api.TransactionKind;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A balance is about to change: for a tax, an anti-cheat or a limit plugin to refuse it.
 * Cancelling it refuses the change, and the caller is told
 * {@link co.xenastudios.xmoney.api.TransactionResult.Status#CANCELLED}.
 *
 * <p>Fired on whichever thread made the change: a Vault call from a shop's async task
 * fires it on that thread, marked asynchronous. Keep handlers short: a shop is waiting.
 * Not fired for imports. Only built when something listens for it.
 *
 * @since 1.0.0
 */
public final class PreTransactionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final BigDecimal change;
    private final TransactionKind kind;
    private final String source;
    private final String reason;
    private final UUID other;
    private boolean cancelled;

    /**
     * Made by xMoney.
     *
     * @param async whether it's fired off the server's main thread
     * @param player whose balance changes (the payer, for a transfer)
     * @param change how much it changes by: negative when money leaves it
     * @param kind what's moving it
     * @param source who or what is moving it: a plugin's name, a player's or "Console"
     * @param reason the few words given with it, or empty
     * @param other the other player in a payment or transfer, or null
     * @since 1.0.0
     */
    public PreTransactionEvent(boolean async, UUID player, BigDecimal change, TransactionKind kind, String source,
                               String reason, UUID other) {
        super(async);
        this.player = player;
        this.change = change;
        this.kind = kind;
        this.source = source;
        this.reason = reason;
        this.other = other;
    }

    /**
     * Whose balance is about to change: the payer, for a payment or transfer.
     *
     * @return the player's uuid
     * @since 1.0.0
     */
    public UUID player() {
        return player;
    }

    /**
     * How much the balance is about to change by: negative when money leaves it. For a
     * staff {@code /eco set}, the difference from the balance now.
     *
     * @return the change, with the currency's decimals
     * @since 1.0.0
     */
    public BigDecimal change() {
        return change;
    }

    /**
     * What's moving the money.
     *
     * @return the kind
     * @since 1.0.0
     */
    public TransactionKind kind() {
        return kind;
    }

    /**
     * Who or what is moving it: the plugin's name for {@link TransactionKind#PLUGIN}, the
     * player's or staff member's name for commands, or "Console".
     *
     * @return the source
     * @since 1.0.0
     */
    public String source() {
        return source;
    }

    /**
     * The few words given with it, such as "vote reward", or empty.
     *
     * @return the reason
     * @since 1.0.0
     */
    public String reason() {
        return reason;
    }

    /**
     * The player on the other side of a payment or transfer: who's paid.
     *
     * @return their uuid, or null when there's no other player
     * @since 1.0.0
     */
    public UUID other() {
        return other;
    }

    /**
     * Whether the change is refused.
     *
     * @return whether it's cancelled
     * @since 1.0.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Refuses the change, or lets it through again.
     *
     * @param cancel true to refuse it
     * @since 1.0.0
     */
    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
