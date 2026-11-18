package co.xenastudios.xplots.protect;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.model.Plots;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Who may change what in a field. In a rented plot, its owner and members may build,
 * open containers and use doors and buttons; nobody else may. The rest of a field (its
 * paths, and above and below its plots) and plot signs belong to staff alone, as do
 * free plots. {@code xplots.bypass} may do anything.
 *
 * <p>Finding the plot for a block is a box check and some arithmetic, the same for ten
 * plots or ten thousand; a block outside every field stops at the first check. Changes
 * that cross from one plot to another (pistons, flowing liquids, fire, trees growing)
 * are stopped at the edge. Registered only while at least one field exists.
 */
public final class Protection extends ListenerModule {

    public static final String BYPASS = "xplots.bypass";

    private final XPlots xplots;
    private final Plots plots;
    // When each player was last told, so holding a click doesn't flood the action bar.
    private final Map<UUID, Long> told = new HashMap<>();

    public Protection(XPlots plugin) {
        super(plugin);
        this.xplots = plugin;
        this.plots = plugin.plots();
    }

    /** What a block is to us: outside every field, a plot, or a protected spot. */
    private Plot at(Block block) {
        return plots.at(block);
    }

    /** Whether the player may change this block. */
    public boolean mayBuild(Player player, Block block) {
        Plot sign = plots.signAt(block);
        if (sign != null) {
            return player.hasPermission(BYPASS);
        }
        Plot plot = at(block);
        return plot == null || mayBuild(player, plot);
    }

    private boolean mayBuild(Player player, Plot plot) {
        if (plot != Field.PATH && plot.lease() != null && plot.lease().trusts(player.getUniqueId())
            && !xplots.resets().isBusy(plot)) {
            return true;
        }
        return player.hasPermission(BYPASS);
    }

    private void deny(Cancellable event, Player player) {
        event.setCancelled(true);
        tell(player);
    }

    private void tell(Player player) {
        long now = System.currentTimeMillis();
        Long last = told.get(player.getUniqueId());
        if (last == null || now - last > 1500) {
            told.put(player.getUniqueId(), now);
            player.sendActionBar(xplots.config().messages().protectedHere().render());
        }
    }

    private void check(Cancellable event, Player player, Block block) {
        if (!mayBuild(player, block)) {
            deny(event, player);
        }
    }

    // ---- players ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        check(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState state : multi.getReplacedBlockStates()) {
                if (!mayBuild(event.getPlayer(), state.getBlock())) {
                    deny(event, event.getPlayer());
                    return;
                }
            }
        }
        check(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        check(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        check(event, event.getPlayer(), event.getBlockClicked());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getAction() == Action.PHYSICAL) {
            // Trampling farmland, pressure plates and tripwires.
            if (!mayBuild(player, block)) {
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (plots.signAt(block) != null) {
            return; // Plot signs are clicked to rent; the sign listener handles that.
        }
        Plot plot = at(block);
        if (plot == null || mayBuild(player, plot)) {
            return;
        }
        Material type = block.getType();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Punching is harmless, except to dragon eggs and note blocks, and to fire, which it puts out.
            if (type == Material.DRAGON_EGG || type == Material.NOTE_BLOCK
                || block.getRelative(event.getBlockFace()).getType() == Material.FIRE) {
                deny(event, player);
            }
            return;
        }
        boolean refused = false;
        if (plot != Field.PATH) {
            // Nothing in someone else's plot is theirs to use: chests, doors, beds, pots, all of it.
            event.setUseInteractedBlock(Event.Result.DENY);
            refused = looksUsable(type);
        } else if (isContainer(block)) {
            // A path's doors and buttons, which staff put there, are for everyone; its chests aren't.
            event.setUseInteractedBlock(Event.Result.DENY);
            refused = true;
        }
        // Tools that change the block they're used on. Food and swords still work.
        if (event.hasItem() && changesBlocks(event.getItem().getType())) {
            event.setUseItemInHand(Event.Result.DENY);
            refused = true;
        }
        if (refused) {
            tell(player);
        }
    }

    // Only decides whether to say why: a right click on plain floor while eating says nothing.
    @SuppressWarnings("deprecation")
    private static boolean looksUsable(Material type) {
        return type.isInteractable();
    }

    private static boolean changesBlocks(Material item) {
        return Tag.ITEMS_HOES.isTagged(item) || Tag.ITEMS_AXES.isTagged(item) || Tag.ITEMS_SHOVELS.isTagged(item)
            || Tag.ITEMS_BOATS.isTagged(item) || Tag.ITEMS_CHEST_BOATS.isTagged(item) || item.name().endsWith("_SPAWN_EGG")
            || item.name().endsWith("MINECART") || switch (item) {
                case BONE_MEAL, FLINT_AND_STEEL, FIRE_CHARGE, HONEYCOMB, SHEARS, BRUSH, ARMOR_STAND, END_CRYSTAL,
                     POTION, SPLASH_POTION, GLASS_BOTTLE, ITEM_FRAME, GLOW_ITEM_FRAME, PAINTING, INK_SAC, GLOW_INK_SAC,
                     LEAD, WIND_CHARGE -> true;
                default -> false;
            };
    }

    private static boolean isContainer(Block block) {
        return block.getState(false) instanceof org.bukkit.inventory.InventoryHolder;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        check(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        check(event, event.getPlayer(), event.getLectern().getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        for (BlockState state : event.getBlocks()) {
            if (player != null ? !mayBuild(player, state.getBlock()) : crosses(event.getBlock(), state.getBlock())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---- entities in plots: item frames, paintings, armour stands ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null) {
            check(event, event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = player(event.getRemover());
        Block block = event.getEntity().getLocation().getBlock();
        if (player != null) {
            check(event, player, block);
        } else if (at(block) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreakOther(HangingBreakEvent event) {
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && at(event.getEntity().getLocation().getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof org.bukkit.entity.Hanging || entity instanceof org.bukkit.entity.ArmorStand) {
            check(event, event.getPlayer(), entity.getLocation().getBlock());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        check(event, event.getPlayer(), event.getRightClicked().getLocation().getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof org.bukkit.entity.Hanging || entity instanceof org.bukkit.entity.ArmorStand)) {
            return; // PvP and mobs aren't ours.
        }
        Player player = player(event.getDamager());
        Block block = entity.getLocation().getBlock();
        if (player != null) {
            check(event, player, block);
        } else if (at(block) != null) {
            event.setCancelled(true);
        }
    }

    private static Player player(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---- the world: nothing crosses into a field, or from one plot to another ----

    /** Whether a change spreading from one block to another crosses a plot's edge, or touches a sign. */
    private boolean crosses(Block from, Block to) {
        if (plots.signAt(to) != null) {
            return true;
        }
        Plot target = at(to);
        if (target == null) {
            return false;
        }
        // Path to path is fine: water staff put on a path may flow along it.
        Plot source = from.getWorld() == to.getWorld() ? at(from) : null;
        return source != target;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filter(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filter(event.blockList());
    }

    // Explosions don't break anything in a field.
    private void filter(List<Block> blocks) {
        if (!blocks.isEmpty() && plots.hasFields(blocks.get(0).getWorld())) {
            blocks.removeIf(block -> at(block) != null || plots.signAt(block) != null);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    private boolean pistonCrosses(Block piston, List<Block> moved, BlockFace direction) {
        if (!plots.hasFields(piston.getWorld())) {
            return false;
        }
        Block head = piston.getRelative(direction);
        if (crosses(piston, head)) {
            return true;
        }
        for (Block block : moved) {
            if (crosses(piston, block) || crosses(piston, block.getRelative(direction))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (crosses(event.getBlock(), event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() == Material.FIRE && crosses(event.getSource(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block source = event.getIgnitingBlock();
        if (at(event.getBlock()) != null && (source == null || crosses(source, event.getBlock()) || at(event.getBlock()) == Field.PATH)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            check(event, player, event.getBlock());
        } else if (event.getIgnitingBlock() != null ? crosses(event.getIgnitingBlock(), event.getBlock())
            : at(event.getBlock()) != null || plots.signAt(event.getBlock()) != null) {
            // Lightning, fireballs and the like start no fires in a field.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent event) {
        if (event.getPrimingEntity() instanceof Player player) {
            check(event, player, event.getBlock());
        } else if (event.getPrimingBlock() != null && crosses(event.getPrimingBlock(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (event.getEntity() instanceof Player player) {
            check(event, player, block);
            return;
        }
        Plot plot = at(block);
        if (plot == null && plots.signAt(block) == null) {
            return;
        }
        // Falling sand and gravel landing in a plot are part of building there.
        if (event.getEntity() instanceof org.bukkit.entity.FallingBlock falling && plot != null && plot != Field.PATH) {
            Location origin = falling.getOrigin();
            if (origin != null && origin.getWorld() == block.getWorld() && at(origin.getBlock()) == plot) {
                return;
            }
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Block sapling = event.getLocation().getBlock();
        Player player = event.getPlayer();
        event.getBlocks().removeIf(state -> player != null && !player.hasPermission(BYPASS)
            ? !mayBuild(player, state.getBlock()) || crosses(sapling, state.getBlock())
            : player == null && crosses(sapling, state.getBlock()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block dispenser = event.getBlock();
        if (dispenser.getBlockData() instanceof org.bukkit.block.data.Directional directional
            && crosses(dispenser, dispenser.getRelative(directional.getFacing()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        // Snow golems' trails, and concrete powder setting, are fine; only signs are kept clear.
        if (plots.signAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Forgets players who left, so the map doesn't grow. */
    public void forget(UUID player) {
        told.remove(player);
    }

    /** Whether any field is in this world; for callers outside events. */
    public boolean covers(World world) {
        return plots.hasFields(world);
    }

}
