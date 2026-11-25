package co.xenastudios.xsample.bungee;

import co.xenastudios.core.loader.bungee.BungeeBootstrap;

/** The main class: the core's Java shim for BungeeCord, pointed at xSample's Kotlin entry point. */
public final class XSampleBungeeBootstrap extends BungeeBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xsample.bungee.XSampleBungee";
    }
}
