package co.xenastudios.zchat.chat;

import co.xenastudios.core.ListenerModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/** Keeps {@link Formats#papiInstalled()} right when PlaceholderAPI is enabled or disabled after zChat. */
public final class PapiWatch extends ListenerModule {

    public PapiWatch(Plugin plugin) {
        super(plugin);
    }

    @EventHandler
    void onEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) {
            Formats.refreshPapi();
        }
    }

    @EventHandler
    void onDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) {
            Formats.refreshPapi();
        }
    }
}
