package co.xenastudios.xrewind.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xRewind's Kotlin entry point. */
public final class XRewindBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xrewind.bukkit.XRewindBukkit";
    }
}
