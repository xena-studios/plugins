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
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/** The quieter kinds of change, off by default. */
public final class Small {

    private Small() {}

    /** Doors, trapdoors, gates, levers and buttons players use: "who opened my door". */
    public static final class Clicks extends ListenerModule {
        private final LogQueue queue;

        public Clicks(Plugin plugin, LogQueue queue) {
            super(plugin);
            this.queue = queue;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        void onClick(PlayerInteractEvent event) {
            Block block = event.getClickedBlock();
            if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.useInteractedBlock() == Event.Result.DENY) {
                return;
            }
            BlockData data = block.getBlockData();
            if (data instanceof Openable || data instanceof Powerable) {
                queue.add(new Entry.BlockChange(Clock.now(), Actor.of(event.getPlayer()), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(), Actions.CLICK, data, data));
            }
        }
    }

    /** Leaves decaying ({@code #decay}). */
    public static final class LeafDecay extends ListenerModule {
        private final LogQueue queue;
        private final Actor decay = Actor.source("decay");
        private final BlockData air = Material.AIR.createBlockData();

        public LeafDecay(Plugin plugin, LogQueue queue) {
            super(plugin);
            this.queue = queue;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        void onDecay(LeavesDecayEvent event) {
            Block block = event.getBlock();
            queue.add(new Entry.BlockChange(Clock.now(), decay, block.getWorld().getName(), block.getX(), block.getY(),
                block.getZ(), Actions.BREAK, block.getBlockData(), air));
        }
    }
}
