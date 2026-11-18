package co.xenastudios.xvote.play;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xvote.XVote;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Joins and quits: a joining player gets the votes held for their name and is paid for
 * everything unpaid, then reminded if they can vote. Keeps who is online for the
 * receiver and database threads.
 */
public final class Players extends ListenerModule {

    private final XVote xvote;
    private final Map<UUID, Player> online = new ConcurrentHashMap<>();

    public Players(XVote plugin) {
        super(plugin);
        this.xvote = plugin;
    }

    /** The player, if online here. Any thread. */
    public Player get(UUID id) {
        return online.get(id);
    }

    @Override
    public void enable() {
        super.enable();
        // Players already online when xVote starts (a /reload): treat them as joining now.
        for (Player player : Bukkit.getOnlinePlayers()) {
            joined(player, false);
        }
    }

    @Override
    public void disable() {
        super.disable();
        for (Player player : online.values()) {
            xvote.votes().quit(player.getUniqueId(), player.getName());
        }
        online.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        joined(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        online.remove(player.getUniqueId(), player);
        xvote.votes().quit(player.getUniqueId(), player.getName());
    }

    private void joined(Player player, boolean remind) {
        online.put(player.getUniqueId(), player);
        xvote.votes().joined(player.getUniqueId(), player.getName());
        var settings = xvote.config().reminders();
        if (remind && settings.onJoin()) {
            long ticks = Math.max(1, settings.joinDelaySeconds() * 20L);
            player.getScheduler().runDelayed(plugin, task -> xvote.remind(player), null, ticks);
        }
    }
}
