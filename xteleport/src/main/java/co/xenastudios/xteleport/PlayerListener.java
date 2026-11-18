package co.xenastudios.xteleport;

import co.xenastudios.core.ListenerModule;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Reads a player's homes as they join, and forgets them, their requests and spent cooldowns as they leave. */
final class PlayerListener extends ListenerModule {

    private final XTeleport plugin;

    PlayerListener(XTeleport plugin) {
        super(plugin);
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent event) {
        if (plugin.store().ready()) {
            // Read off the server's threads; until it's back, /home says "not available".
            plugin.homes().of(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        plugin.homes().forget(player);
        plugin.requests().forget(player);
        plugin.teleporter().cooldowns().prune();
    }
}
