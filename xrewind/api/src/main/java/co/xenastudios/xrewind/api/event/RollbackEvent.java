package co.xenastudios.xrewind.api.event;

import co.xenastudios.xrewind.api.Plan;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A rollback, restore or undo is about to be applied: from a command
 * ({@code /xr rollback}, {@code restore}, {@code apply}, {@code undo}) or another plugin's
 * {@link co.xenastudios.xrewind.api.XRewindApi#apply}. Cancel it to stop it; nothing is
 * changed.
 *
 * <p>Fired on a server thread (the main thread on Paper), only when something listens
 * for it.
 *
 * @since 1.1.0
 */
public final class RollbackEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plan plan;
    private final CommandSender sender;
    private boolean cancelled;

    /**
     * Made by xRewind; other plugins listen for it.
     *
     * @param plan what will change
     * @param sender who ran the command, or null for another plugin
     * @since 1.1.0
     */
    public RollbackEvent(@NotNull Plan plan, @Nullable CommandSender sender) {
        this.plan = plan;
        this.sender = sender;
    }

    /**
     * What will change.
     *
     * @return the plan
     * @since 1.1.0
     */
    public @NotNull Plan getPlan() {
        return plan;
    }

    /**
     * Who ran the command.
     *
     * @return the sender, or null when another plugin applied it through the API
     * @since 1.1.0
     */
    public @Nullable CommandSender getSender() {
        return sender;
    }

    /**
     * Whether it won't be applied.
     *
     * @return true if cancelled
     * @since 1.1.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Stops it. A command's sender isn't told, so tell them why.
     *
     * @param cancel true to cancel
     * @since 1.1.0
     */
    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    /**
     * This event's listeners.
     *
     * @return the handlers
     * @since 1.1.0
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Every listener for this event, as Bukkit needs.
     *
     * @return the handlers
     * @since 1.1.0
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
