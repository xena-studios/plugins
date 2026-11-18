package co.xenastudios.zchat.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player was mentioned in chat and is about to be pinged (hear the mention sound).
 * Cancel it to keep them from hearing it; their name is still highlighted.
 *
 * <p>Fired on the mentioned player's thread (the main thread on Paper, their region
 * thread on Folia), after the message was sent. Only built when something listens for
 * it.
 *
 * @since 3.2.0
 */
public final class MentionEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final boolean everyone;
    private boolean cancelled;

    /**
     * Made by zChat; other plugins listen for it.
     *
     * @param mentioned the player about to be pinged
     * @param sender who mentioned them
     * @param everyone true if they were pinged by {@code @everyone} rather than by name
     * @since 3.2.0
     */
    public MentionEvent(@NotNull Player mentioned, @NotNull Player sender, boolean everyone) {
        super(mentioned);
        this.sender = sender;
        this.everyone = everyone;
    }

    /**
     * Who sent the message that mentions the player. {@link #getPlayer()} is the player
     * mentioned.
     *
     * @return the sender
     * @since 3.2.0
     */
    public @NotNull Player getSender() {
        return sender;
    }

    /**
     * Whether the ping is from {@code @everyone} rather than the player's name.
     *
     * @return true for {@code @everyone}
     * @since 3.2.0
     */
    public boolean isEveryone() {
        return everyone;
    }

    /**
     * Whether the player won't be pinged.
     *
     * @return true if cancelled
     * @since 3.2.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Keeps the player from being pinged.
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
