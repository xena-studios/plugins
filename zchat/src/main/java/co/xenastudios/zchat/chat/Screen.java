package co.xenastudios.zchat.chat;

import co.xenastudios.zchat.ZChat;
import co.xenastudios.zchat.config.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Runs what a player typed past the filter, for chat and private messages alike. A
 * player who may use styles is filtered on their text with the tags taken out, so
 * {@code bad<reset>word} is still a bad word.
 *
 * @param blocked whether the filter, in block mode, stopped it
 * @param plain the words as the filter and anti-repeat see them
 * @param message how it appears
 */
public record Screen(boolean blocked, String plain, Component message) {

    public static Screen of(ZChat zchat, Player player, String typed) {
        ZChat.Compiled compiled = zchat.compiled();
        boolean styled = compiled.formats().mayStyle(player);
        String plain = styled ? Formats.withoutStyles(typed) : typed;
        if (!compiled.filter().isEmpty() && !player.hasPermission(ChatListener.BYPASS_FILTER)) {
            ChatFilter.Result result = compiled.filter().apply(plain);
            if (result.matched()) {
                if (zchat.config().filter().mode() == Settings.FilterMode.BLOCK) {
                    return new Screen(true, plain, Component.empty());
                }
                // Censored text loses its styles: they can't be lined up with what was hidden.
                return new Screen(false, result.text(), Component.text(result.text()));
            }
        }
        return new Screen(false, plain, Formats.playerText(typed, styled));
    }
}
