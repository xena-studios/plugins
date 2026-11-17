package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.Plugin;

/**
 * Blocks changed by mobs and other entities: endermen, withers, ravagers, falling sand,
 * players trampling farmland. Animals going about their day (sheep eating grass,
 * villagers farming) aren't logged: it's constant, and nobody needs it undone.
 */
public final class Mobs extends ListenerModule {

    private static final Set<EntityType> EVERYDAY = EnumSet.of(EntityType.SHEEP, EntityType.VILLAGER, EntityType.BEE,
        EntityType.FOX, EntityType.TURTLE, EntityType.SNOW_GOLEM, EntityType.RABBIT, EntityType.ALLAY);

    private final LogQueue queue;

    public Mobs(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onChange(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (EVERYDAY.contains(entity.getType())) {
            return;
        }
        Block block = event.getBlock();
        BlockData before = block.getBlockData();
        BlockData after = event.getBlockData();
        Actor actor = entity instanceof Player player ? Actor.of(player) : Actor.source(entity.getType().getKey().getKey());
        queue.add(new Entry.BlockChange(Clock.now(), actor, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
            after.getMaterial().isAir() ? Actions.BREAK : Actions.PLACE, before, after));
    }
}
