package co.xenastudios.xvote.velocity;

import co.xenastudios.core.loader.velocity.VelocityBootstrap;

/** The main class: the core's Java shim for Velocity, pointed at xVote's Kotlin entry point. */
public final class XVoteVelocityBootstrap extends VelocityBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xvote.velocity.XVoteVelocity";
    }
}
