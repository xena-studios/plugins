package co.xenastudios.xstats.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xStats' Kotlin entry point. */
public final class XStatsBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xstats.bukkit.XStatsBukkit";
    }
}
