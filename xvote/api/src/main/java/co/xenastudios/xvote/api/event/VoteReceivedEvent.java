package co.xenastudios.xvote.api.event;

import co.xenastudios.xvote.api.VoteSource;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A vote has arrived and is about to be stored. Fired off the server's threads, on
 * whichever thread received it, and only built when something listens. Cancelling it
 * drops the vote: it isn't stored, counted or rewarded.
 *
 * @since 1.0.0
 */
public final class VoteReceivedEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String site;
    private final String username;
    private final String address;
    private final String timestamp;
    private final VoteSource source;
    private boolean cancelled;

    /**
     * Made by xVote.
     *
     * @param site the site's service name, as the list sent it
     * @param username the name the list sent
     * @param address the voter's address, as the list sent it; may be empty
     * @param timestamp the list's own timestamp, as it sent it; may be empty
     * @param source where the vote came from
     * @since 1.0.0
     */
    public VoteReceivedEvent(String site, String username, String address, String timestamp, VoteSource source) {
        super(true);
        this.site = site;
        this.username = username;
        this.address = address;
        this.timestamp = timestamp;
        this.source = source;
    }

    /**
     * The site's service name, as the list sent it.
     *
     * @return the service name
     * @since 1.0.0
     */
    public String getSite() {
        return site;
    }

    /**
     * The name the list sent.
     *
     * @return the voter's name
     * @since 1.0.0
     */
    public String getUsername() {
        return username;
    }

    /**
     * The voter's address, as the list sent it.
     *
     * @return the address, or an empty string
     * @since 1.0.0
     */
    public String getAddress() {
        return address;
    }

    /**
     * The list's own timestamp, unchanged. Lists disagree on its format.
     *
     * @return the timestamp, or an empty string
     * @since 1.0.0
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Where the vote came from.
     *
     * @return the source
     * @since 1.0.0
     */
    public VoteSource getSource() {
        return source;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handler list
     * @since 1.0.0
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
