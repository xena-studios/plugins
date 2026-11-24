package co.xenastudios.xtwin.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, pointed at xTwin's Kotlin entry point. */
public final class XTwinBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xtwin.bukkit.XTwinBukkit";
    }
}
