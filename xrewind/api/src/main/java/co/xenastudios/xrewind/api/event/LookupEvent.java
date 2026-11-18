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
 * <p>It describes the lookup as it runs: a command's parameters as xRewind read them
 * (with the sender's position for a radius, and the inspected block for the
 * inspector), and which page of the results it shows.
 *
 * <p>Fired asynchronously, as the lookup starts, only when something listens for it.
 *
 * @since 1.1.0
 */
public final class LookupEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender sender;
    private final Query query;
    private final int page;

    /**
     * Made by xRewind; other plugins listen for it.
     *
     * @param sender who looked, or null for another plugin
     * @param query what they looked for, as it runs
     * @param page which page of the results is shown, from 1
     * @since 1.1.0
     */
    public LookupEvent(@Nullable CommandSender sender, @NotNull Query query, int page) {
        super(true);
        this.sender = sender;
        this.query = query;
        this.page = page;
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
     * What they looked for, as it runs. {@link Query#toString()} gives it as
     * {@code /xr lookup} parameters. Its {@link Query#limit() limit} is the entries per
     * page: for a command, the page the chat shows.
     *
     * @return the query
     * @since 1.1.0
     */
    public @NotNull Query getQuery() {
        return query;
    }

    /**
     * Which page of the results is shown, from 1: the entries from
     * {@code (page - 1) * limit + 1} to {@code page * limit}, newest first. It's 1 for a
     * new lookup and another plugin's; {@code /xr page} looks again for a later one.
     *
     * @return the page
     * @since 1.1.0
     */
    public int getPage() {
        return page;
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
