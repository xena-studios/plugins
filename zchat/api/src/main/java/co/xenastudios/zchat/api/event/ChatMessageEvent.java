package co.xenastudios.zchat.api.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A chat message passed zChat's checks (the mute, cooldown, filter and anti-repeat) and
 * is about to be drawn in its format. Cancel it to stop the message, or add placeholder
 * values for the format to use.
 *
 * <p>This is a pre-event: it fires at zChat's place in Paper's {@code AsyncChatEvent}
 * ({@code LOW} priority), before other plugins have had their say, so the message may
 * still not be sent: another plugin can cancel it afterwards. To act only on messages
 * that really go out, such as to log them, listen for {@link ChatMessageSentEvent}.
 *
 * <p>Fired on the chat thread the message arrived on, so usually asynchronously (see
 * {@link #isAsynchronous()}); many may fire at once. Only built when something listens
 * for it.
 *
 * @since 3.2.0
 */
public final class ChatMessageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private static final Pattern TAG_NAME = Pattern.compile("[a-z0-9_-]+");
    /** The placeholders and theme tags zChat's formats already have. */
    private static final Set<String> TAKEN = Set.of(
        "player", "displayname", "message", "papi", "prefix", "highlight", "success", "error");

    private final Player player;
    private final Component message;
    private final String formatName;
    private final Map<String, Component> placeholders = new LinkedHashMap<>();
    private boolean cancelled;

    /**
     * Made by zChat; other plugins listen for it.
     *
     * @param async whether it's fired off the server's main thread
     * @param player who sent the message
     * @param message the message, after the filter and mentions
     * @param formatName the format it'll be drawn in
     * @since 3.2.0
     */
    public ChatMessageEvent(boolean async, @NotNull Player player, @NotNull Component message, @NotNull String formatName) {
        super(async);
        this.player = player;
        this.message = message;
        this.formatName = formatName;
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
     * The message as it'll appear: after the filter, with mentions highlighted. To change
     * it, change Paper's {@code AsyncChatEvent} message at a lower priority than zChat's
     * ({@code LOW}).
     *
     * @return the message
     * @since 3.2.0
     */
    public @NotNull Component getMessage() {
        return message;
    }

    /**
     * The format the message will be drawn in: a group's name under
     * {@code formatting.groups}, or {@code "default"}. While zChat doesn't draw chat
     * ({@code formatting.enabled: false}), it's still the format the player would get,
     * from the groups in the config, though the message isn't drawn in it.
     *
     * @return the format's name
     * @since 3.2.0
     */
    public @NotNull String getFormatName() {
        return formatName;
    }

    /**
     * Gives the format a {@code <name>} tag with this value, for this message only. An
     * owner can then write it in a format, such as {@code <clan> <player>: <message>}.
     * Only used while zChat draws chat ({@code formatting.enabled}).
     *
     * @param name the tag's name: lowercase letters, digits, {@code _} and {@code -}, and
     *     not one zChat's formats already have ({@code player}, {@code displayname},
     *     {@code message}, {@code papi}, and the theme's {@code prefix},
     *     {@code highlight}, {@code success} and {@code error}); avoid MiniMessage's
     *     own tag names, such as {@code bold}
     * @param value what the tag shows; clicks and hovers in it work
     * @throws IllegalArgumentException if the name isn't allowed
     * @since 3.2.0
     */
    public void addPlaceholder(@NotNull String name, @NotNull Component value) {
        if (!TAG_NAME.matcher(name).matches() || TAKEN.contains(name)) {
            throw new IllegalArgumentException("\"" + name + "\" can't be a placeholder name");
        }
        placeholders.put(name, value);
    }

    /**
     * The placeholders added so far.
     *
     * @return each tag's name and value, in the order they were added
     * @since 3.2.0
     */
    public @NotNull Map<String, Component> getPlaceholders() {
        return Collections.unmodifiableMap(placeholders);
    }

    /**
     * Whether the message won't be sent. False doesn't promise it will be: another
     * plugin can still cancel it.
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
