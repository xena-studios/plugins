package co.xenastudios.zchat.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.zchat.ZChat;
import co.xenastudios.zchat.chat.ChatState;
import co.xenastudios.zchat.config.Settings;
import co.xenastudios.zchat.config.Toggle;
import co.xenastudios.zchat.config.ZConfig;
import java.util.Collections;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;

/** /clearchat, /mutechat and /togglechat. */
public final class ChatCommands {

    public static final String BYPASS_CLEAR = "zchat.bypass.clearchat";

    private ChatCommands() {}

    static CommandSpec.Builder named(String name, Toggle settings) {
        return CommandSpec.named(name).aliases(settings.aliases()).permission(settings.permission());
    }

    public static void register(Setup setup, ZChat zchat, ZConfig config) {
        ChatState state = zchat.state();

        Settings.ClearChat clear = config.clearchat();
        if (clear.enabled()) {
            setup.permission(BYPASS_CLEAR, PermissionDefault.OP);
            // One message of blank lines: a single packet per player, not one per line.
            Component blank = Component.text(String.join("\n", Collections.nCopies(clear.lines(), " ")));
            setup.command(named("clearchat", clear)
                .description("Clear chat for everyone.")
                .executes(invocation -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (!player.hasPermission(BYPASS_CLEAR)) {
                            player.sendMessage(blank);
                        }
                    }
                    everyone(clear.messageCleared().chat(Placeholders.text("player", invocation.sender().getName())));
                }));
        }

        Settings.MuteChat mute = config.mutechat();
        if (mute.enabled()) {
            setup.command(named("mutechat", mute)
                .description("Mute or unmute chat for everyone.")
                .executes(invocation -> {
                    boolean muted = !state.muted();
                    state.muted(muted);
                    everyone((muted ? mute.messageMuted() : mute.messageUnmuted())
                        .chat(Placeholders.text("player", invocation.sender().getName())));
                }));
        }

        Settings.ToggleChat toggle = config.togglechat();
        if (toggle.enabled()) {
            setup.command(named("togglechat", toggle)
                .permissionDefault(PermissionDefault.TRUE)
                .description("Hide or show other players' chat.")
                .executes(invocation -> {
                    Player player = invocation.player();
                    boolean hide = !state.hidesChat(player.getUniqueId());
                    state.hideChat(player.getUniqueId(), hide);
                    if (toggle.remember()) {
                        zchat.tasks().entity(player, () -> {
                            if (hide) {
                                player.getPersistentDataContainer().set(zchat.hiddenChatKey(), PersistentDataType.BOOLEAN, true);
                            } else {
                                player.getPersistentDataContainer().remove(zchat.hiddenChatKey());
                            }
                        });
                    }
                    (hide ? toggle.messageOff() : toggle.messageOn()).send(player);
                }));
        }
    }

    /** Every player and the console. */
    private static void everyone(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }
}
