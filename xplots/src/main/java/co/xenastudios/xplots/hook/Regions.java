package co.xenastudios.xplots.hook;

import co.xenastudios.xplots.model.Box;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * WorldGuard regions xPlots makes so a world-wide {@code build deny} doesn't block its
 * plots: one over each field, plus one for each plot made by itself outside its field.
 * They allow building, and xPlots' own protection decides who may. Without WorldGuard,
 * nothing happens.
 */
public interface Regions {

    /** Makes or replaces a region over these columns, from the bottom of the world to the top. */
    void define(String id, World world, Box columns);

    void remove(String id, World world);

    static Regions find(Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return NONE;
        }
        try {
            return new WorldGuardRegions(logger);
        } catch (LinkageError e) {
            logger.warning("This WorldGuard isn't one xPlots can use (" + e + "), so fields get no region.");
            return NONE;
        }
    }

    Regions NONE = new Regions() {
        @Override
        public void define(String id, World world, Box columns) {}

        @Override
        public void remove(String id, World world) {}
    };
}
