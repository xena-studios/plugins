package co.xenastudios.xbans.bungee;

import co.xenastudios.core.loader.bungee.BungeeBootstrap;

/** The main class: the core's Java shim for BungeeCord, pointed at xBans' Kotlin entry point. */
public final class XBansBungeeBootstrap extends BungeeBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xbans.bungee.XBansBungee";
    }
}
