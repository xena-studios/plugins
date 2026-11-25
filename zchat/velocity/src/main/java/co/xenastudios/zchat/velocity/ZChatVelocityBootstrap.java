package co.xenastudios.zchat.velocity;

import co.xenastudios.core.loader.velocity.VelocityBootstrap;

/** The main class: the core's Java shim for Velocity, pointed at zChat's Kotlin entry point. */
public final class ZChatVelocityBootstrap extends VelocityBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.zchat.velocity.ZChatVelocity";
    }
}
