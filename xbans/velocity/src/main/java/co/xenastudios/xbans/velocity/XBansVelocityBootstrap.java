package co.xenastudios.xbans.velocity;

import co.xenastudios.core.loader.velocity.VelocityBootstrap;

/** The main class: the core's Java shim for Velocity, pointed at xBans' Kotlin entry point. */
public final class XBansVelocityBootstrap extends VelocityBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xbans.velocity.XBansVelocity";
    }
}
