package co.xenastudios.xvote.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xVote's Kotlin entry point. */
public final class XVoteBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xvote.bukkit.XVoteBukkit";
    }
}
