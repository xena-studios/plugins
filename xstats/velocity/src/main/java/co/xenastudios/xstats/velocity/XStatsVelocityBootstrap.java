package co.xenastudios.xstats.velocity;

import co.xenastudios.core.loader.velocity.VelocityBootstrap;

/** The main class: the core's Java shim for Velocity, pointed at xStats' Kotlin entry point. */
public final class XStatsVelocityBootstrap extends VelocityBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xstats.velocity.XStatsVelocity";
    }
}
