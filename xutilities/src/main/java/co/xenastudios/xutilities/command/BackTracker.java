package co.xenastudios.xutilities.command;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xutilities.api.event.BackRecordEvent;
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
    // Read by the API from any thread.
    private volatile boolean enabled;

    public BackTracker(Plugin plugin) {
        super(plugin);
    }

    /** Whether /back is on, so spots are remembered. */
    public boolean enabled() {
        return enabled;
    }

    /** Where /back takes the player, or null. The stored location: don't change it. */
    public Location previous(Player player) {
        return previous.get(player.getUniqueId());
    }

    /**
     * Sets where /back takes the player, for the API. Does nothing while /back is off or
     * for a player who isn't online, since only quitting removes a spot.
     */
    public boolean remember(Player player, Location location) {
        if (!enabled || !player.isOnline()) {
            return false;
        }
        previous.put(player.getUniqueId(), location);
        return true;
    }

    public void forget(Player player) {
        previous.remove(player.getUniqueId());
    }

    @Override
    public void enable() {
        super.enable();
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
        super.disable();
        previous.clear();
    }

    /** Saves the spot unless a listener of BackRecordEvent cancels it; the event is only built when one listens. */
    private void record(Player player, Location location, BackRecordEvent.Reason reason) {
        if (BackRecordEvent.getHandlerList().getRegisteredListeners().length > 0
            && !new BackRecordEvent(player, location, reason).callEvent()) {
            return;
        }
        previous.put(player.getUniqueId(), location);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTeleport(PlayerTeleportEvent event) {
        // isOnline: a plugin teleporting someone as they quit (spawn on quit, combat
        // log) mustn't leave an entry behind that nothing removes.
        if (CAUSES.contains(event.getCause()) && event.getPlayer().isOnline()) {
            record(event.getPlayer(), event.getFrom(), BackRecordEvent.Reason.TELEPORT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onDeath(PlayerDeathEvent event) {
        // Combat-log plugins kill players as they quit; that death isn't one to go back to.
        if (event.getPlayer().isOnline()) {
            record(event.getPlayer(), event.getPlayer().getLocation(), BackRecordEvent.Reason.DEATH);
        }
    }

    // Last, after any other plugin's quit handling.
    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        previous.remove(event.getPlayer().getUniqueId());
    }
}
