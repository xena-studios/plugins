package co.xenastudios.zchat.api.event;

import co.xenastudios.zchat.api.MessageKind;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * zChat's filter caught something in a chat message or private message, for logging and
 * staff alerts. By the time it fires, the message has already been blocked or censored,
 * as the filter's {@code mode} says.
 *
 * <p>Fired on the thread the message was sent from: a chat thread for chat (usually
 * asynchronously), the command's thread for a private message (see
 * {@link #isAsynchronous()}). Only built when something listens for it.
 *
 * @since 3.2.0
 */
public final class ZChatFilterEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final MessageKind kind;
    private final String message;
    private final List<String> matches;
    private final boolean blocked;

    /**
     * Made by zChat; other plugins listen for it.
     *
     * @param async whether it's fired off the server's main thread
     * @param player who sent the message
     * @param kind a chat message or a private message
     * @param message what the filter checked
     * @param matches the parts of it that matched
     * @param blocked true if the message was blocked; false if it was censored
     * @since 3.2.0
     */
    public ZChatFilterEvent(boolean async, @NotNull Player player, @NotNull MessageKind kind, @NotNull String message,
                            @NotNull List<String> matches, boolean blocked) {
        super(async);
        this.player = player;
        this.kind = kind;
        this.message = message;
        this.matches = List.copyOf(matches);
        this.blocked = blocked;
    }

    /**
     * Who sent the message.
     *
     * @return the player
     * @since 3.2.0
     */
    public @NotNull Player getPlayer() {
        return player;
    }

    /**
     * Whether it was a chat message or a private message.
     *
     * @return the kind
     * @since 3.2.0
     */
    public @NotNull MessageKind getKind() {
        return kind;
    }

    /**
     * The message the filter checked, as typed (with any styles the player may use taken
     * out).
     *
     * @return the message
     * @since 3.2.0
     */
    public @NotNull String getMessage() {
        return message;
    }

    /**
     * The parts of the message that matched a filter word or pattern, as typed, in the
     * order they appear.
     *
     * @return the matches; never empty
     * @since 3.2.0
     */
    public @NotNull List<String> getMatches() {
        return matches;
    }

    /**
     * Whether the message was blocked (filter mode {@code block}) rather than sent
     * censored.
     *
     * @return true if blocked
     * @since 3.2.0
     */
    public boolean isBlocked() {
        return blocked;
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
