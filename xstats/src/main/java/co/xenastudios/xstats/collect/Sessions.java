package co.xenastudios.xstats.collect;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.data.Event;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Joins and quits: a session starts, and a player's stats are read as they leave. The
 * only two events xStats listens for; reading stats needs no listener at all.
 */
public final class Sessions extends ListenerModule {

    private final Engine engine;
    private final Reader reader;

    public Sessions(Plugin plugin, Engine engine, Reader reader) {
        super(plugin);
        this.engine = engine;
        this.reader = reader;
    }

    // First, before other plugins' join listeners: a first-join reward that adds to a stat
    // through the API finds the player already known, instead of being dropped.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location look = player.getLocation();
        engine.queue(new Event.Joined(player.getUniqueId(), player.getName(), System.currentTimeMillis(), player.getFirstPlayed(),
            look.getYaw(), look.getPitch()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.queue(reader.read(event.getPlayer(), true, true));
    }
}
