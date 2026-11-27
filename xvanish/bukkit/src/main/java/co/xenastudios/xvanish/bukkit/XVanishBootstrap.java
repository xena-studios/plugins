package co.xenastudios.xvanish.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xVanish's Kotlin entry point. */
public final class XVanishBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xvanish.bukkit.XVanishBukkit";
    }
}
