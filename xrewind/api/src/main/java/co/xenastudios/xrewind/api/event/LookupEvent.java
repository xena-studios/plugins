package co.xenastudios.xrewind.api.event;

import co.xenastudios.xrewind.api.Query;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Someone looked up the history, for an audit log: with {@code /xr lookup},
 * {@code /xr near}, {@code /xr page}, the inspector, or another plugin's
 * {@link co.xenastudios.xrewind.api.XRewindApi#lookup}. Rollbacks aren't lookups; listen
 * for {@link RollbackEvent} for those.
 *
 * <p>Fired asynchronously, as the lookup starts, only when something listens for it.
 *
 * @since 1.1.0
 */
public final class LookupEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender sender;
    private final Query query;

    /**
     * Made by xRewind; other plugins listen for it.
     *
     * @param sender who looked, or null for another plugin
     * @param query what they looked for
     * @since 1.1.0
     */
    public LookupEvent(@Nullable CommandSender sender, @NotNull Query query) {
        super(true);
        this.sender = sender;
        this.query = query;
    }

    /**
     * Who looked.
     *
     * @return the sender, or null when another plugin looked through the API
     * @since 1.1.0
     */
    public @Nullable CommandSender getSender() {
        return sender;
    }

    /**
     * What they looked for. {@link Query#toString()} gives it as {@code /xr lookup}
     * parameters.
     *
     * @return the query
     * @since 1.1.0
     */
    public @NotNull Query getQuery() {
        return query;
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
