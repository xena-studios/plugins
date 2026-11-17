package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Joins and quits, with where the player was. */
public final class Sessions extends ListenerModule {

    private final LogQueue queue;

    public Sessions(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent event) {
        log(event.getPlayer(), Actions.JOIN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        log(event.getPlayer(), Actions.QUIT);
    }

    private void log(Player player, byte action) {
        Location at = player.getLocation();
        queue.add(new Entry.Session(Clock.now(), Actor.of(player), at.getWorld().getName(),
            at.getBlockX(), at.getBlockY(), at.getBlockZ(), action));
    }
}
