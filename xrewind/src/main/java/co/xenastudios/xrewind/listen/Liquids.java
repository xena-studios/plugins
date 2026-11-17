package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.Plugin;

/**
 * Buckets emptied and filled by players, and flowing water or lava destroying a block
 * (crops, torches, rails). Plain spreading isn't logged: it would be most of the log.
 */
public final class Liquids extends ListenerModule {

    private final LogQueue queue;
    private final Actor water = Actor.source("water");
    private final Actor lava = Actor.source("lava");
    private final BlockData air = Material.AIR.createBlockData();

    public Liquids(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    // The busiest event there is: out at once unless something's in the way.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        Material type = to.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
            return;
        }
        Block from = event.getBlock();
        Material liquid = from.getType();
        if (liquid != Material.WATER && liquid != Material.LAVA) {
            return;
        }
        queue.add(new Entry.BlockChange(Clock.now(), liquid == Material.LAVA ? lava : water, to.getWorld().getName(),
            to.getX(), to.getY(), to.getZ(), Actions.BREAK, to.getBlockData(), from.getBlockData()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        BlockData before = block.getBlockData();
        BlockData after;
        if (before instanceof Waterlogged logged && event.getBucket() != Material.LAVA_BUCKET) {
            Waterlogged wet = (Waterlogged) logged.clone();
            wet.setWaterlogged(true);
            after = wet;
        } else {
            after = switch (event.getBucket()) {
                case LAVA_BUCKET -> Material.LAVA.createBlockData();
                case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW.createBlockData();
                default -> Material.WATER.createBlockData();
            };
        }
        queue.add(new Entry.BlockChange(Clock.now(), Actor.of(event.getPlayer()), block.getWorld().getName(),
            block.getX(), block.getY(), block.getZ(), Actions.PLACE, before, after));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        BlockData before = block.getBlockData();
        BlockData after = air;
        if (before instanceof Waterlogged logged && logged.isWaterlogged()) {
            Waterlogged dry = (Waterlogged) logged.clone();
            dry.setWaterlogged(false);
            after = dry;
        }
        queue.add(new Entry.BlockChange(Clock.now(), Actor.of(event.getPlayer()), block.getWorld().getName(),
            block.getX(), block.getY(), block.getZ(), Actions.BREAK, before, after));
    }
}
