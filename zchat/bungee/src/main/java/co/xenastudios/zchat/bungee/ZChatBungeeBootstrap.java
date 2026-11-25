package co.xenastudios.zchat.bungee;

import co.xenastudios.core.loader.bungee.BungeeBootstrap;

/** The main class: the core's Java shim for BungeeCord, pointed at zChat's Kotlin entry point. */
public final class ZChatBungeeBootstrap extends BungeeBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.zchat.bungee.ZChatBungee";
    }
}
