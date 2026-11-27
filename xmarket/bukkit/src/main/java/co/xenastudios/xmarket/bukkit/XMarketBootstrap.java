package co.xenastudios.xmarket.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xMarket's Kotlin entry point. */
public final class XMarketBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xmarket.bukkit.XMarketBukkit";
    }
}
