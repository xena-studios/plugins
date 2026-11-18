package co.xenastudios.zchat.api.event;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A private message sent with {@code /msg} or {@code /reply} passed zChat's checks (mute
 * providers and the filter) and is about to be delivered. Cancel it to stop it, or change
 * the message.
 *
 * <p>Fired on the thread the command runs on (see {@link #isAsynchronous()}). Only built
 * when something listens for it.
 *
 * @since 3.2.0
 */
public final class PrivateMessageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender sender;
    private final CommandSender recipient;
    private final boolean reply;
    private Component message;
    private boolean cancelled;

    /**
     * Made by zChat; other plugins listen for it.
     *
     * @param async whether it's fired off the server's main thread
     * @param sender who sent it: a player, the console, or a command block
     * @param recipient who it's for: a player or the console
     * @param message the message, after the filter
     * @param reply true if it was sent with {@code /reply}
     * @since 3.2.0
     */
    public PrivateMessageEvent(boolean async, @NotNull CommandSender sender, @NotNull CommandSender recipient,
                               @NotNull Component message, boolean reply) {
        super(async);
        this.sender = sender;
        this.recipient = recipient;
        this.message = message;
        this.reply = reply;
    }

    /**
     * Who sent the message.
     *
     * @return a player, the console, or a command block
     * @since 3.2.0
     */
    public @NotNull CommandSender getSender() {
        return sender;
    }

    /**
     * Who the message is for.
     *
     * @return a player or the console
     * @since 3.2.0
     */
    public @NotNull CommandSender getRecipient() {
        return recipient;
    }

    /**
     * Whether it was sent with {@code /reply} rather than {@code /msg}.
     *
     * @return true for {@code /reply}
     * @since 3.2.0
     */
    public boolean isReply() {
        return reply;
    }

    /**
     * The message, after the filter.
     *
     * @return the message
     * @since 3.2.0
     */
    public @NotNull Component getMessage() {
        return message;
    }

    /**
     * Changes what's delivered, to both sides and to social spies.
     *
     * @param message the new message
     * @since 3.2.0
     */
    public void setMessage(@NotNull Component message) {
        this.message = message;
    }

    /**
     * Whether the message won't be delivered.
     *
     * @return true if cancelled
     * @since 3.2.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Stops the message. The sender isn't told, so tell them why.
     *
     * @param cancel true to cancel
     * @since 3.2.0
     */
    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    /**
     * This event's listeners.
     *
     * @return the handlers
     * @since 3.2.0
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Every listener for this event, as Bukkit needs.
     *
     * @return the handlers
     * @since 3.2.0
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
