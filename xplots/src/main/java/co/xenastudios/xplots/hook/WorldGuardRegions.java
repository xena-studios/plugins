package co.xenastudios.xplots.hook;

import co.xenastudios.xplots.model.Box;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import java.util.logging.Logger;
import org.bukkit.World;

/** Only loaded when WorldGuard is installed. */
final class WorldGuardRegions implements Regions {

    private final Logger logger;

    WorldGuardRegions(Logger logger) {
        this.logger = logger;
        // Fails here, not later, if this WorldGuard's API doesn't match.
        WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    private RegionManager manager(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    @Override
    public void define(String id, World world, Box columns) {
        RegionManager manager = manager(world);
        if (manager == null) {
            logger.warning("WorldGuard has regions turned off in " + world.getName() + ", so " + id + " wasn't made.");
            return;
        }
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(columns.minX(), world.getMinHeight(), columns.minZ()),
            BlockVector3.at(columns.maxX(), world.getMaxHeight() - 1, columns.maxZ()));
        // xPlots decides who may build and open things here. PvP is left alone.
        region.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        region.setFlag(Flags.USE, StateFlag.State.ALLOW);
        region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.ALLOW);
        region.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        region.setPriority(1);
        manager.addRegion(region);
    }

    @Override
    public void remove(String id, World world) {
        RegionManager manager = manager(world);
        if (manager != null) {
            manager.removeRegion(id);
        }
    }
}
