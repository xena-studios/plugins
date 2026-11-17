package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;

/**
 * Blocks destroyed by explosions. TNT lit by a player is put down to that player;
 * otherwise to the cause: {@code #tnt}, {@code #creeper}, {@code #bed}...
 */
public final class Explosions extends ListenerModule {

    private final LogQueue queue;
    private final RecentBreaks recent;
    private final BlockData air = Material.AIR.createBlockData();

    public Explosions(Plugin plugin, LogQueue queue, RecentBreaks recent) {
        super(plugin);
        this.queue = queue;
        this.recent = recent;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEntityExplode(EntityExplodeEvent event) {
        log(event.blockList(), cause(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockExplode(BlockExplodeEvent event) {
        Material type = event.getExplodedBlockState().getType();
        String cause = Tag.BEDS.isTagged(type) ? "bed" : type == Material.RESPAWN_ANCHOR ? "respawn_anchor" : "explosion";
        log(event.blockList(), Actor.source(cause));
    }

    private void log(List<Block> blocks, Actor actor) {
        int time = Clock.now();
        for (Block block : blocks) {
            BlockBreaks.log(block, actor, time, queue, air);
            recent.broke(block, actor);
        }
    }

    static Actor cause(Entity entity) {
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return Actor.of(player);
        }
        return Actor.source(entity.getType().getKey().getKey());
    }
}
