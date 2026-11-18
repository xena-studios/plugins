package co.xenastudios.zchat;

import co.xenastudios.core.Module;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.zchat.api.MuteProvider;
import co.xenastudios.zchat.api.ZChatApi;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * zChat's developer API, offered through the services manager while zChat is enabled.
 * It answers from the state chat already keeps, so it adds nothing to run.
 */
final class Api implements ZChatApi, Module {

    /** zChat's broadcast style: the theme's prefix and colour, as /mutechat's announcement has. */
    private static final Message BROADCAST = Message.of("<prefix><message>");

    private final ZChat zchat;

    Api(ZChat zchat) {
        this.zchat = zchat;
    }

    @Override
    public void enable() {
        Bukkit.getServicesManager().register(ZChatApi.class, this, zchat, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        Bukkit.getServicesManager().unregister(ZChatApi.class, this);
    }

    @Override
    public void registerMuteProvider(Plugin plugin, MuteProvider provider) {
        zchat.mutes().add(Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(provider, "provider"));
    }

    @Override
    public void unregisterMuteProvider(MuteProvider provider) {
        zchat.mutes().remove(provider);
    }

    @Override
    public void broadcast(Component message) {
        Component line = BROADCAST.chat(Placeholders.component("message", message));
        // zChat's own list of who's online: safe from any thread, unlike the server's.
        for (Player player : zchat.state().online()) {
            if (!hasChatHidden(player)) {
                player.sendMessage(line);
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    @Override
    public boolean send(Player player, Component message) {
        if (hasChatHidden(player)) {
            return false;
        }
        player.sendMessage(BROADCAST.chat(Placeholders.component("message", message)));
        return true;
    }

    @Override
    public boolean hasChatHidden(Player player) {
        return zchat.config().togglechat().enabled() && zchat.state().hidesChat(player.getUniqueId());
    }

    @Override
    public boolean isChatMuted() {
        return zchat.state().muted();
    }

    @Override
    public String getFormatName(Player player) {
        return zchat.compiled().formats().formatName(player, DEFAULT_FORMAT);
    }
}
