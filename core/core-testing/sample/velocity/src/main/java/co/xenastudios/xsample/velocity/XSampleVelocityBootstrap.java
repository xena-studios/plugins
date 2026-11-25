package co.xenastudios.xsample.velocity;

import co.xenastudios.core.loader.velocity.VelocityBootstrap;

/** The main class: the core's Java shim for Velocity, pointed at xSample's Kotlin entry point. */
public final class XSampleVelocityBootstrap extends VelocityBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xsample.velocity.XSampleVelocity";
    }
}
