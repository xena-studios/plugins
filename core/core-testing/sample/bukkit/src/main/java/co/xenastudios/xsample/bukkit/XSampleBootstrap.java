package co.xenastudios.xsample.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, pointed at xSample's Kotlin entry point. */
public final class XSampleBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xsample.bukkit.XSampleBukkit";
    }
}
