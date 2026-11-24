package co.xenastudios.loadersandbox;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, pointed at the Kotlin entry point. */
public final class LoaderSandboxBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.loadersandbox.LoaderSandbox";
    }
}
