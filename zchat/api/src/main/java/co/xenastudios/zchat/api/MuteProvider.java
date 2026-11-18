package co.xenastudios.zchat.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Says whether a player is muted, for a punishment plugin. Register one with
 * {@link ZChatApi#registerMuteProvider}; zChat asks it before every chat message and
 * private message a player sends.
 *
 * <p>It's asked on the thread the message is sent from (a chat thread, many at once) and
 * on the hot path of every message, so it must answer from memory: never query a
 * database or wait for anything.
 *
 * @since 3.2.0
 */
@FunctionalInterface
public interface MuteProvider {

    /**
     * Whether the player may send this message.
     *
     * @param player the player sending it
     * @param kind a chat message or a private message
     * @return null if they may; otherwise what to tell them, which zChat sends as it is
     *     and the message isn't sent
     * @since 3.2.0
     */
    @Nullable Component muteReason(Player player, MessageKind kind);
}
