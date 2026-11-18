package co.xenastudios.zchat.api;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * zChat's developer API: mute providers, broadcasts in zChat's style, and what zChat
 * knows about chat.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered
 * while zChat is enabled. Every method may be called from any thread, answers from
 * memory and returns immediately.
 *
 * @since 3.2.0
 */
public interface ZChatApi {

    /**
     * What {@link #getFormatName(Player)} returns for a player in none of the groups: the
     * {@code default-format}.
     *
     * @since 3.2.0
     */
    String DEFAULT_FORMAT = "default";

    /**
     * zChat's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if zChat isn't installed and enabled
     * @since 3.2.0
     */
    static ZChatApi get() {
        ZChatApi api = Bukkit.getServicesManager().load(ZChatApi.class);
        if (api == null) {
            throw new IllegalStateException("zChat isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * Asks this provider, before every chat message and private message a player sends,
     * whether they're muted. Providers are asked in the order they were registered, and
     * the first reason given wins. A provider whose plugin is disabled is forgotten, and
     * one that throws is logged once and removed, so it can't break chat. Registering the
     * same provider again does nothing. Any thread.
     *
     * @param plugin the plugin the provider belongs to
     * @param provider the provider
     * @since 3.2.0
     */
    void registerMuteProvider(Plugin plugin, MuteProvider provider);

    /**
     * Stops asking this provider. Any thread.
     *
     * @param provider a provider registered with {@link #registerMuteProvider}
     * @since 3.2.0
     */
    void unregisterMuteProvider(MuteProvider provider);

    /**
     * Sends a message to every player who hasn't hidden chat with {@code /togglechat},
     * and to the console, in zChat's style: the theme's prefix and colour, as zChat's own
     * announcements have. Any thread.
     *
     * @param message the message, without a prefix
     * @since 3.2.0
     */
    void broadcast(Component message);

    /**
     * Sends a message to one player in zChat's style (the theme's prefix and colour),
     * unless they hid chat with {@code /togglechat}. Any thread.
     *
     * @param player the player
     * @param message the message, without a prefix
     * @return true if it was sent; false if they hid chat
     * @since 3.2.0
     */
    boolean send(Player player, Component message);

    /**
     * Whether a player hid other players' chat with {@code /togglechat}. Always false
     * while {@code /togglechat} is off. Any thread, answers from memory.
     *
     * @param player the player
     * @return true if they hid chat
     * @since 3.2.0
     */
    boolean hasChatHidden(Player player);

    /**
     * Whether chat is muted for everyone with {@code /mutechat}. Any thread, answers
     * from memory.
     *
     * @return true if chat is muted
     * @since 3.2.0
     */
    boolean isChatMuted();

    /**
     * Which chat format a player gets: the name of their group under
     * {@code formatting.groups} with the highest weight, or {@link #DEFAULT_FORMAT}. Any
     * thread, answers from memory (it checks the player's permissions).
     *
     * @param player the player
     * @return the group's name, or {@value #DEFAULT_FORMAT}
     * @since 3.2.0
     */
    String getFormatName(Player player);
}
