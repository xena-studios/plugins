package co.xenastudios.xteleport.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xTeleport's Kotlin entry point. */
public final class XTeleportBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xteleport.bukkit.XTeleportBukkit";
    }
}
