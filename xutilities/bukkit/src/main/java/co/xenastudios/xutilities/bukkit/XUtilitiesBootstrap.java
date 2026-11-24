package co.xenastudios.xutilities.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xUtilities' Kotlin entry point. */
public final class XUtilitiesBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xutilities.bukkit.XUtilitiesBukkit";
    }
}
