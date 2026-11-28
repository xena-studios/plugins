package co.xenastudios.xlimbo.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;
import org.bukkit.generator.ChunkGenerator;

/**
 * The main class: the core's Java shim, which loads the Kotlin stdlib, then xLimbo's Kotlin entry
 * point. It also answers the server's ask for xLimbo's generator, for a world named in bukkit.yml
 * ({@code generator: xLimbo}), which can come before the plugin is enabled.
 */
public final class XLimboBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xlimbo.bukkit.XLimboBukkit";
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        try {
            return (ChunkGenerator) Class.forName("co.xenastudios.xlimbo.bukkit.Generators", true, getClass().getClassLoader())
                .getMethod("forWorld", java.io.File.class, String.class)
                .invoke(null, getDataFolder(), worldName);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            getLogger().warning("Couldn't make xLimbo's generator for world " + worldName + ": " + e);
            return null;
        }
    }
}
