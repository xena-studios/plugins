package co.xenastudios.xplots.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xPlots' Kotlin entry point. */
public final class XPlotsBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xplots.bukkit.XPlotsBukkit";
    }
}
