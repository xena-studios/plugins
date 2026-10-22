package co.xenastudios.xutilities.command;

import co.xenastudios.core.ListenerModule;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

/**
 * Remembers where each player was before their last teleport, or where they died, for
 * /back. Runs only while /back is on, and forgets players when they leave.
 */
public final class BackTracker extends ListenerModule {

    /** Teleports worth going back from: commands and plugins, not pearls or portals. */
    private static final Set<TeleportCause> CAUSES = Set.of(TeleportCause.COMMAND, TeleportCause.PLUGIN, TeleportCause.UNKNOWN);

    // Concurrent: on Folia, players on different regions teleport at the same time.
    private final Map<UUID, Location> previous = new ConcurrentHashMap<>();

    public BackTracker(Plugin plugin) {
        super(plugin);
    }

    Location previous(Player player) {
        return previous.get(player.getUniqueId());
    }

    @Override
    public void disable() {
        super.disable();
        previous.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTeleport(PlayerTeleportEvent event) {
        if (CAUSES.contains(event.getCause())) {
            previous.put(event.getPlayer().getUniqueId(), event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onDeath(PlayerDeathEvent event) {
        previous.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation());
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        previous.remove(event.getPlayer().getUniqueId());
    }
}
