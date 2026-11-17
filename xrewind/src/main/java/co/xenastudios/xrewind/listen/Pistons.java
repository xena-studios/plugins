package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.Plugin;

/**
 * Blocks pistons move ({@code #piston}): each block's new spot gets it, and a spot it
 * left that nothing moved into becomes air, so a rollback puts everything back.
 */
public final class Pistons extends ListenerModule {

    private final LogQueue queue;
    private final Actor piston = Actor.source("piston");
    private final BlockData air = Material.AIR.createBlockData();

    public Pistons(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), facing(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onRetract(BlockPistonRetractEvent event) {
        // Pulled blocks move back towards the piston.
        move(event.getBlocks(), facing(event.getBlock()).getOppositeFace());
    }

    private static BlockFace facing(Block piston) {
        return piston.getBlockData() instanceof Directional directional ? directional.getFacing() : BlockFace.UP;
    }

    private void move(List<Block> blocks, BlockFace direction) {
        if (blocks.isEmpty()) {
            return;
        }
        int time = Clock.now();
        Map<Block, BlockData> arriving = new HashMap<>();
        for (Block block : blocks) {
            arriving.put(block.getRelative(direction), block.getBlockData());
        }
        for (Map.Entry<Block, BlockData> to : arriving.entrySet()) {
            Block block = to.getKey();
            queue.add(new Entry.BlockChange(time, piston, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                Actions.PLACE, block.getBlockData(), to.getValue()));
        }
        for (Block block : blocks) {
            if (!arriving.containsKey(block)) {
                queue.add(new Entry.BlockChange(time, piston, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                    Actions.BREAK, block.getBlockData(), air));
            }
        }
    }
}
