package co.xenastudios.xplots.play;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Lease;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.model.SignPlacement;
import co.xenastudios.xplots.setup.Selections;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Clicking plot signs, the wand, and keeping signs and warnings up to date. */
public final class PlayListener extends ListenerModule {

    private final XPlots xplots;

    public PlayListener(XPlots plugin) {
        super(plugin);
        this.xplots = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (xplots.selections().isWand(event.getItem()) && player.hasPermission(xplots.adminPermission())) {
            event.setCancelled(true);
            wand(player, event.getAction(), block, event.getBlockFace());
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Plot plot = xplots.plots().signAt(block);
        if (plot == null) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission(XPlots.USE)) {
            xplots.config().messages().error().noPermission().send(player);
            return;
        }
        Lease lease = plot.lease();
        if (lease == null) {
            xplots.menus().rent(player, plot);
        } else if (lease.owner().equals(player.getUniqueId())) {
            xplots.menus().plot(player, plot);
        } else {
            xplots.config().messages().rentedBy().send(player, xplots.rentals().info(plot).placeholders());
        }
    }

    private void wand(Player player, Action action, Block block, BlockFace face) {
        boolean sneaking = player.isSneaking();
        if (action == Action.LEFT_CLICK_BLOCK && sneaking) {
            Plot plot = xplots.plots().at(block);
            if (plot == null || plot == Field.PATH) {
                plot = xplots.plots().signAt(block);
            }
            if (plot == null || plot == Field.PATH) {
                player.sendMessage(xplots.staffMessage("<error>That isn't in a plot."));
            } else {
                xplots.menus().admin(player, plot);
            }
        } else if (action == Action.RIGHT_CLICK_BLOCK && sneaking) {
            Plot sign = xplots.plots().signAt(block);
            if (sign != null) {
                turnSign(player, sign);
            } else {
                moveSign(player, block.getRelative(face));
            }
        } else if (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK) {
            int which = action == Action.LEFT_CLICK_BLOCK ? 1 : 2;
            Selections.Selection selection = xplots.selections().corner(player.getName(), which,
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
            var box = selection.box();
            String size = box == null ? "" : " (" + box.width() + " × " + box.height() + " × " + box.depth() + ")";
            player.sendMessage(xplots.staffMessage("Corner " + which + " set at <highlight>" + block.getX() + " "
                + block.getY() + " " + block.getZ() + "</highlight>" + size + "."));
        }
    }

    // Turns a plot's sign 45° clockwise, where it stands.
    private void turnSign(Player player, Plot plot) {
        BlockFace facing = SignPlacement.turn(plot.signFacing());
        xplots.plots().moveSign(plot, plot.signX(), plot.signY(), plot.signZ(), facing);
        xplots.store().savePlot(plot);
        xplots.signs().place(plot);
        player.sendMessage(xplots.staffMessage("Turned <highlight>" + plot.id() + "</highlight>'s sign to face "
            + facing.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "."));
    }

    // Moves the nearest plot's sign to where the player pointed, facing them.
    private void moveSign(Player player, Block target) {
        Plot nearest = null;
        double best = 16 * 16;
        for (Field field : xplots.plots().fields()) {
            if (!field.world().equals(target.getWorld().getName())) {
                continue;
            }
            for (Plot plot : field.plots()) {
                var box = plot.box();
                double dx = Math.max(0, Math.max(box.minX() - target.getX(), target.getX() - box.maxX()));
                double dz = Math.max(0, Math.max(box.minZ() - target.getZ(), target.getZ() - box.maxZ()));
                double distance = dx * dx + dz * dz;
                if (distance < best) {
                    best = distance;
                    nearest = plot;
                }
            }
        }
        if (nearest == null) {
            player.sendMessage(xplots.staffMessage("<error>No plot within 16 blocks of there."));
            return;
        }
        if (xplots.plots().signAt(target) != null || !target.getType().isAir() && !target.isReplaceable()) {
            player.sendMessage(xplots.staffMessage("<error>There's something in the way there."));
            return;
        }
        // Facing the player who placed it, to the nearest 22.5°.
        BlockFace facing = SignPlacement.toward(player.getLocation().getYaw());
        xplots.signs().remove(nearest);
        xplots.plots().moveSign(nearest, target.getX(), target.getY(), target.getZ(), facing);
        xplots.store().savePlot(nearest);
        xplots.signs().place(nearest);
        player.sendMessage(xplots.staffMessage("Moved <highlight>" + nearest.id() + "</highlight>'s sign here."));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        xplots.expiry().joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        xplots.warmups().cancel(player.getUniqueId());
        xplots.selections().forget(player.getName());
        xplots.pending().cancel(player.getName());
        xplots.forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (xplots.plots().signsInChunk(chunk.getWorld(), chunk.getX(), chunk.getZ()).isEmpty()) {
            return;
        }
        // Next tick: changing blocks while a chunk is still loading isn't safe.
        int x = chunk.getX();
        int z = chunk.getZ();
        var world = chunk.getWorld();
        Bukkit.getScheduler().runTask(plugin, () -> xplots.signs().chunkLoaded(world, x, z));
    }
}
