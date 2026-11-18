package co.xenastudios.xrewind.api.event;

import co.xenastudios.xrewind.api.Plan;
import co.xenastudios.xrewind.api.RollbackResult;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A rollback, restore or undo finished: every change it could make is made.
 *
 * <p>Fired on the global region thread (the main thread on Paper), only when something
 * listens for it.
 *
 * @since 1.1.0
 */
public final class RollbackFinishedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plan plan;
    private final RollbackResult result;
    private final CommandSender sender;

    /**
     * Made by xRewind; other plugins listen for it.
     *
     * @param plan what was applied
     * @param result what it did
     * @param sender who ran the command, or null for another plugin
     * @since 1.1.0
     */
    public RollbackFinishedEvent(@NotNull Plan plan, @NotNull RollbackResult result, @Nullable CommandSender sender) {
        this.plan = plan;
        this.result = result;
        this.sender = sender;
    }

    /**
     * What was applied.
     *
     * @return the plan
     * @since 1.1.0
     */
    public @NotNull Plan getPlan() {
        return plan;
    }

    /**
     * What it did, and the plan that undoes it.
     *
     * @return the result
     * @since 1.1.0
     */
    public @NotNull RollbackResult getResult() {
        return result;
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
