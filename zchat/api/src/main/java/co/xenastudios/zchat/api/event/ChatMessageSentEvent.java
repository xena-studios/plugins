package co.xenastudios.zchat.api.event;

import java.util.Collections;
import java.util.Set;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A chat message zChat handled is going out: every plugin has had its say and none
 * cancelled it. For logging, stats and chat bridges, which want only the messages
 * players really see. It can't be cancelled or changed; to do that, listen for
 * {@link ChatMessageEvent}.
 *
 * <p>Fired once zChat has seen Paper's {@code AsyncChatEvent} through at {@code MONITOR}
 * priority, on the chat thread the message arrived on, so usually asynchronously (see
 * {@link #isAsynchronous()}). Not fired for a message zChat stopped (a mute, cooldown,
 * the filter or anti-repeat), or one another plugin cancelled. Only built when something
 * listens for it.
 *
 * @since 3.2.0
 */
public final class ChatMessageSentEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Component message;
    private final Set<Audience> viewers;

    /**
     * Made by zChat; other plugins listen for it.
     *
     * @param async whether it's fired off the server's main thread
     * @param player who sent the message
     * @param message the message as sent, before it's drawn in its format
     * @param viewers who it's sent to
     * @since 3.2.0
     */
    public ChatMessageSentEvent(boolean async, @NotNull Player player, @NotNull Component message, @NotNull Set<Audience> viewers) {
        super(async);
        this.player = player;
        this.message = message;
        this.viewers = Collections.unmodifiableSet(viewers);
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
     * The message as sent: after the filter, with mentions highlighted and any other
     * plugin's changes, before it's drawn in its format.
     *
     * @return the message
     * @since 3.2.0
     */
    public @NotNull Component getMessage() {
        return message;
    }

    /**
     * Who the message is sent to: the players who see it (not those who hid chat), and
     * the console.
     *
     * @return the viewers, which can't be changed
     * @since 3.2.0
     */
    public @NotNull Set<Audience> getViewers() {
        return viewers;
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
