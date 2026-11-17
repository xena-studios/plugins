package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;

/** Mobs and players killed by a player. */
public final class Kills extends ListenerModule {

    private final LogQueue queue;

    public Kills(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) {
            return;
        }
        Location at = dead.getLocation();
        queue.add(new Entry.Kill(Clock.now(), Actor.of(killer), at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
            at.getBlockZ(), dead.getType().getKey().toString()));
    }
}
