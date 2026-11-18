package co.xenastudios.zchat.chat;

import co.xenastudios.zchat.ZChat;
import co.xenastudios.zchat.api.MessageKind;
import co.xenastudios.zchat.api.event.FilterMatchEvent;
import co.xenastudios.zchat.config.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

    public static Screen of(ZChat zchat, Player player, String typed, MessageKind kind) {
        return of(zchat, player, typed, null, kind);
    }

    /**
     * @param kept the message as another plugin left it, shown as it is if the filter
     *     lets it through unchanged; null to draw it from what was typed
     */
    public static Screen of(ZChat zchat, Player player, String typed, Component kept, MessageKind kind) {
        ZChat.Compiled compiled = zchat.compiled();
        boolean styled = compiled.formats().mayStyle(player);
        String plain = styled ? Formats.withoutStyles(typed) : typed;
        if (!compiled.filter().isEmpty() && !player.hasPermission(ChatListener.BYPASS_FILTER)) {
            ChatFilter.Result result = compiled.filter().apply(plain);
            if (result.matched()) {
                boolean block = zchat.config().filter().mode() == Settings.FilterMode.BLOCK;
                // Built only when something listens for it.
                if (FilterMatchEvent.getHandlerList().getRegisteredListeners().length > 0) {
                    new FilterMatchEvent(!Bukkit.isPrimaryThread(), player, kind, plain, result.matches(plain), block).callEvent();
                }
                if (block) {
                    return new Screen(true, plain, Component.empty());
                }
                // Censored text loses its styles: they can't be lined up with what was hidden.
                return new Screen(false, result.text(), Component.text(result.text()));
            }
        }
        return new Screen(false, plain, kept != null ? kept : Formats.playerText(typed, styled));
    }
}
