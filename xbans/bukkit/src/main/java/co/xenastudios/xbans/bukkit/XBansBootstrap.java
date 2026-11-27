package co.xenastudios.xbans.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xBans' Kotlin entry point. */
public final class XBansBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xbans.bukkit.XBansBukkit";
    }
}
