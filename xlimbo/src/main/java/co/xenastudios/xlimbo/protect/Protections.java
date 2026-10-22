package co.xenastudios.xlimbo.protect;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.core.Module;
import co.xenastudios.core.Setup;
import co.xenastudios.xlimbo.XLimbo;
import co.xenastudios.xlimbo.config.Settings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;

/**
 * What xLimbo stops players doing. Each protection is its own listener, registered only
 * while it's on, and each acts only in the limbo world.
 */
public final class Protections {

    private Protections() {}

    public static void register(Setup setup, XLimbo xlimbo, Settings.Protections settings) {
        if (settings.floor()) {
            setup.module("protect.floor", () -> new Floor(xlimbo));
        }
        if (settings.voidProtection().enabled() || settings.voidProtection().cancelVoidDamage()) {
            setup.module("protect.void", () -> new Void(xlimbo));
        }
        if (settings.portals()) {
            setup.module("protect.portals", () -> new Portals(xlimbo));
        }
        if (settings.liquids()) {
            setup.module("protect.liquids", () -> new Liquids(xlimbo));
        }
        if (settings.gravityBlocks()) {
            setup.module("protect.gravity", () -> new Gravity(xlimbo));
        }
        if (settings.fire()) {
            setup.module("protect.fire", () -> new Fire(xlimbo));
        }
        if (settings.explosions()) {
            setup.module("protect.explosions", () -> new Explosions(xlimbo));
        }
        if (settings.mobSpawns()) {
            setup.module("protect.mobs", () -> new Mobs(xlimbo));
        }
        if (settings.redstone()) {
            setup.module("protect.redstone", () -> new Redstone(xlimbo));
        }
        if (settings.itemDrops()) {
            setup.module("protect.items", () -> new Items(xlimbo));
        }
        if (settings.entities().blockEntityItems()) {
            setup.module("protect.entities", () -> new EntityItems(xlimbo));
        }
        int cleanup = settings.entities().cleanupIntervalSeconds();
        if (cleanup > 0) {
            // Keyed by the interval, so a changed interval restarts the sweep.
            setup.module("protect.cleanup." + cleanup, () -> new Cleanup(xlimbo, cleanup));
        }
        if (settings.buildGuard().enabled()) {
            setup.module("protect.build-guard", () -> new BuildGuard(xlimbo));
        }
    }

    /** A protection: a listener that only cares about the limbo world. */
    abstract static class Protection extends ListenerModule {

        final XLimbo xlimbo;

        Protection(XLimbo xlimbo) {
            super(xlimbo);
            this.xlimbo = xlimbo;
        }

        boolean here(World world) {
            return xlimbo.limbo().isLimbo(world);
        }

        boolean here(Block block) {
            return here(block.getWorld());
        }

        boolean here(Entity entity) {
            return here(entity.getWorld());
        }
    }

    static final class Floor extends Protection {

        Floor(XLimbo xlimbo) {
            super(xlimbo);
        }

        private boolean isFloor(Block block) {
            return block.getY() == xlimbo.limbo().floorY() && here(block);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onBreak(BlockBreakEvent event) {
            if (isFloor(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        // Nothing goes in or under the floor.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPlace(BlockPlaceEvent event) {
            if (event.getBlock().getY() <= xlimbo.limbo().floorY() && here(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPistonPush(BlockPistonExtendEvent event) {
            cancelIfFloor(event, event.getBlocks());
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPistonPull(BlockPistonRetractEvent event) {
            cancelIfFloor(event, event.getBlocks());
        }

        private void cancelIfFloor(Cancellable event, List<Block> moved) {
            for (Block block : moved) {
                if (isFloor(block)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onEntityChange(EntityChangeBlockEvent event) {
            if (isFloor(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        // Even with explosions allowed, they can't hole the floor.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onEntityExplode(EntityExplodeEvent event) {
            event.blockList().removeIf(this::isFloor);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onBlockExplode(BlockExplodeEvent event) {
            event.blockList().removeIf(this::isFloor);
        }

        // An ice floor doesn't melt, a leaf floor doesn't decay.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onFade(BlockFadeEvent event) {
            if (isFloor(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onDecay(LeavesDecayEvent event) {
            if (isFloor(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Void extends Protection {

        Void(XLimbo xlimbo) {
            super(xlimbo);
        }

        // On every move, so the cheapest check (height) goes first.
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        void onMove(PlayerMoveEvent event) {
            Settings.Void settings = xlimbo.config().protections().voidProtection();
            Location to = event.getTo();
            // Never above the floor, or players standing on it would be caught every step.
            int floorY = xlimbo.limbo().floorY();
            if (to.getY() < Math.min(settings.thresholdY(), floorY) && settings.enabled() && here(to.getWorld())) {
                // Straight back up onto the floor: no teleport needed, just a new destination.
                event.setTo(new Location(to.getWorld(), to.getX(), floorY + 1, to.getZ(), to.getYaw(), to.getPitch()));
                event.getPlayer().setFallDistance(0);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onDamage(EntityDamageEvent event) {
            EntityDamageEvent.DamageCause cause = event.getCause();
            if ((cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.FALL)
                && event.getEntity() instanceof Player
                && xlimbo.config().protections().voidProtection().cancelVoidDamage()
                && here(event.getEntity())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Portals extends Protection {

        Portals(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onCreate(PortalCreateEvent event) {
            if (here(event.getWorld())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPlayerPortal(PlayerPortalEvent event) {
            if (here(event.getFrom().getWorld())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onEntityPortal(EntityPortalEvent event) {
            if (here(event.getFrom().getWorld())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        void onEye(PlayerInteractEvent event) {
            Block block = event.getClickedBlock();
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && block.getType() == Material.END_PORTAL_FRAME
                && event.getItem() != null && event.getItem().getType() == Material.ENDER_EYE && here(block)) {
                event.setCancelled(true);
            }
        }
    }

    static final class Liquids extends Protection {

        Liquids(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onBucket(PlayerBucketEmptyEvent event) {
            if (here(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onFlow(BlockFromToEvent event) {
            if (here(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onDispense(BlockDispenseEvent event) {
            Material item = event.getItem().getType();
            if ((item == Material.WATER_BUCKET || item == Material.LAVA_BUCKET || item == Material.POWDER_SNOW_BUCKET) && here(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Gravity extends Protection {

        Gravity(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPlace(BlockPlaceEvent event) {
            if (event.getBlock().getType().hasGravity() && here(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        // A block placed some other way (a command, a plugin) stays put rather than falling.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onFall(EntityChangeBlockEvent event) {
            if (event.getEntity() instanceof FallingBlock && event.getTo().isAir() && here(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Fire extends Protection {

        Fire(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onIgnite(BlockIgniteEvent event) {
            if (here(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onBurn(BlockBurnEvent event) {
            if (here(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onSpread(BlockSpreadEvent event) {
            Material source = event.getSource().getType();
            if ((source == Material.FIRE || source == Material.SOUL_FIRE) && here(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Explosions extends Protection {

        Explosions(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPrime(ExplosionPrimeEvent event) {
            if (here(event.getEntity())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onEntityExplode(EntityExplodeEvent event) {
            if (here(event.getEntity())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onBlockExplode(BlockExplodeEvent event) {
            if (here(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Mobs extends Protection {

        Mobs(XLimbo xlimbo) {
            super(xlimbo);
        }

        // Every way a mob appears but a plugin spawning one on purpose.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && here(event.getEntity())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Redstone extends Protection {

        Redstone(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW)
        void onRedstone(BlockRedstoneEvent event) {
            if (event.getNewCurrent() != 0 && here(event.getBlock())) {
                event.setNewCurrent(0);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPiston(BlockPistonExtendEvent event) {
            if (here(event.getBlock())) {
                event.setCancelled(true);
            }
        }

        // A redstone block powers a dispenser without a redstone event.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onDispense(BlockDispenseEvent event) {
            if (here(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    static final class Items extends Protection {

        Items(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onDrop(PlayerDropItemEvent event) {
            if (here(event.getPlayer())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onItem(ItemSpawnEvent event) {
            if (here(event.getEntity())) {
                event.setCancelled(true);
            }
        }
    }

    static final class EntityItems extends Protection {

        EntityItems(XLimbo xlimbo) {
            super(xlimbo);
        }

        // Armour stands, boats, minecarts and End crystals.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPlace(EntityPlaceEvent event) {
            if (here(event.getEntity())) {
                event.setCancelled(true);
            }
        }

        // Item frames and paintings.
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onHang(HangingPlaceEvent event) {
            if (here(event.getEntity())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        void onSpawnEgg(PlayerInteractEvent event) {
            ItemStack item = event.getItem();
            if (item != null && item.getType().name().endsWith("_SPAWN_EGG") && here(event.getPlayer())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onEggSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG && here(event.getEntity())) {
                event.setCancelled(true);
            }
        }
    }

    /** Removes stray entities other than players every so often. */
    static final class Cleanup implements Module {

        private final XLimbo xlimbo;
        private final int seconds;
        private ScheduledTask task;

        Cleanup(XLimbo xlimbo, int seconds) {
            this.xlimbo = xlimbo;
            this.seconds = seconds;
        }

        @Override
        public void enable() {
            task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(xlimbo, scheduled -> sweep(), seconds * 20L, seconds * 20L);
        }

        @Override
        public void disable() {
            task.cancel();
        }

        private void sweep() {
            World world = xlimbo.limbo().world();
            if (world == null) {
                return;
            }
            for (Entity entity : world.getEntities()) {
                if (stray(entity)) {
                    entity.remove();
                }
            }
        }

        // What players leave behind. Not players, and not what staff place on purpose:
        // displays, markers, interactions, frames and paintings, armour stands, or
        // anything tagged xlimbo.keep.
        private static boolean stray(Entity entity) {
            return !(entity instanceof Player || entity instanceof Display || entity instanceof Marker
                || entity instanceof Interaction || entity instanceof Hanging || entity instanceof ArmorStand)
                && !entity.getScoreboardTags().contains("xlimbo.keep");
        }
    }

    /** Limits how fast each player places blocks, against lag machines. */
    static final class BuildGuard extends Protection {

        // Main thread only: [the second, places in it] per player.
        private final Map<UUID, long[]> places = new HashMap<>();

        BuildGuard(XLimbo xlimbo) {
            super(xlimbo);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        void onPlace(BlockPlaceEvent event) {
            if (!here(event.getBlock())) {
                return;
            }
            long second = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());
            long[] count = places.computeIfAbsent(event.getPlayer().getUniqueId(), id -> new long[2]);
            if (count[0] != second) {
                count[0] = second;
                count[1] = 0;
            }
            if (++count[1] > xlimbo.config().protections().buildGuard().maxPlacesPerSecond()) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        void onQuit(PlayerQuitEvent event) {
            places.remove(event.getPlayer().getUniqueId());
        }
    }
}
