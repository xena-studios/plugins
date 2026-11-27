package co.xenastudios.xvote.bungee;

import co.xenastudios.core.loader.bungee.BungeeBootstrap;

/** The main class: the core's Java shim for BungeeCord, pointed at xVote's Kotlin entry point. */
public final class XVoteBungeeBootstrap extends BungeeBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xvote.bungee.XVoteBungee";
    }
}
