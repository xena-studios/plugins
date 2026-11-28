package co.xenastudios.xlimits.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xLimits' Kotlin entry point. */
public final class XLimitsBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xlimits.bukkit.XLimitsBukkit";
    }
}
