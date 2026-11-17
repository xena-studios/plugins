package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Items players put into or take out of block containers. What the container held is
 * noted when it's opened and compared when it's closed, so a whole visit costs two
 * snapshots, not a log row per click. Two players in one chest at once share the blame:
 * the same trade CoreProtect makes.
 */
public final class Containers extends ListenerModule {

    private record Open(String world, int x, int y, int z, ItemStack[] contents) {}

    private final LogQueue queue;
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();

    public Containers(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        BlockState block = block(inventory.getHolder(false));
        if (block != null) {
            open.put(player.getUniqueId(), new Open(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                copy(inventory.getContents())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Open was = open.remove(player.getUniqueId());
        if (was == null) {
            return;
        }
        Map<ItemStack, Integer> change = diff(was.contents(), event.getInventory().getContents());
        int time = Clock.now();
        Actor actor = Actor.of(player);
        for (Map.Entry<ItemStack, Integer> item : change.entrySet()) {
            int amount = item.getValue();
            if (amount != 0) {
                queue.add(new Entry.ContainerChange(time, actor, was.world(), was.x(), was.y(), was.z(),
                    amount > 0 ? Actions.ADD : Actions.REMOVE, item.getKey(), Math.abs(amount)));
            }
        }
    }

    /** The block a container inventory belongs to: a double chest counts as its left half. */
    static BlockState block(InventoryHolder holder) {
        if (holder instanceof DoubleChest chest) {
            return chest.getLeftSide(false) instanceof BlockState left ? left : null;
        }
        return holder instanceof BlockInventoryHolder && holder instanceof BlockState state ? state : null;
    }

    /** How many of each item (as one of it) there are now compared with before: positive for added. */
    static Map<ItemStack, Integer> diff(ItemStack[] before, ItemStack[] after) {
        Map<ItemStack, Integer> change = count(before, -1);
        for (Map.Entry<ItemStack, Integer> now : count(after, 1).entrySet()) {
            change.merge(now.getKey(), now.getValue(), Integer::sum);
        }
        change.values().removeIf(amount -> amount == 0);
        return change;
    }

    // Each kind of item (one of it, so stacks of the same thing match) and how many, times sign.
    private static Map<ItemStack, Integer> count(ItemStack[] contents, int sign) {
        Map<ItemStack, Integer> counts = new HashMap<>();
        for (ItemStack item : contents) {
            if (item != null && !item.isEmpty()) {
                counts.merge(item.asOne(), sign * item.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }
}
