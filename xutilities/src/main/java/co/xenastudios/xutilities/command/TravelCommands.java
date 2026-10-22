package co.xenastudios.xutilities.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.xutilities.config.Settings;
import co.xenastudios.xutilities.config.XConfig;
import java.util.Set;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/** /top and /back. */
final class TravelCommands {

    /** Blocks that hurt to stand on. */
    private static final Set<Material> UNSAFE = Set.of(
        Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
        Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE);

    private TravelCommands() {}

    /**
     * In a world with a roof, such as the Nether, the highest block is the roof. Look
     * below it instead: the highest solid block with room to stand above it.
     */
    private static Block highestUnderCeiling(Location here) {
        var world = here.getWorld();
        for (int y = world.getLogicalHeight() - 3; y > here.getBlockY(); y--) {
            Block block = world.getBlockAt(here.getBlockX(), y, here.getBlockZ());
            if (block.isSolid()
                && block.getRelative(0, 1, 0).isPassable()
                && block.getRelative(0, 2, 0).isPassable()) {
                return block;
            }
        }
        return null;
    }

    static void register(Setup setup, Tasks tasks, XConfig.Commands commands, BackTracker tracker) {
        Settings.Top top = commands.top();
        if (top.enabled()) {
            setup.command(Specs.named("top", top)
                .description("Teleport to the highest block above you.")
                .executes(invocation -> {
                    Player player = invocation.player();
                    tasks.entity(player, () -> {
                        Location here = player.getLocation();
                        // The column under a player is in the region their thread owns.
                        Block highest = here.getWorld().hasCeiling()
                            ? highestUnderCeiling(here)
                            : here.getWorld().getHighestBlockAt(here, HeightMap.MOTION_BLOCKING);
                        if (highest == null || highest.getY() + 1 <= here.getY() || UNSAFE.contains(highest.getType())) {
                            top.messageUnsafe().send(player);
                            return;
                        }
                        Location destination = highest.getLocation().add(0.5, 1, 0.5);
                        destination.setYaw(here.getYaw());
                        destination.setPitch(here.getPitch());
                        player.teleportAsync(destination, TeleportCause.COMMAND).thenAccept(moved -> {
                            if (moved) {
                                top.messageSuccess().send(player);
                            }
                        });
                    });
                }));
        }

        Settings.Back back = commands.back();
        if (back.enabled()) {
            setup.module("back", () -> tracker);
            setup.command(Specs.named("back", back)
                .description("Go back to where you were before your last teleport, or where you died.")
                .executes(invocation -> {
                    Player player = invocation.player();
                    Location previous = tracker.previous(player);
                    if (previous == null || !previous.isWorldLoaded()) {
                        back.messageNone().send(player);
                        return;
                    }
                    player.teleportAsync(previous, TeleportCause.COMMAND).thenAccept(moved -> {
                        if (moved) {
                            back.messageSuccess().send(player);
                        }
                    });
                }));
        }
    }
}
