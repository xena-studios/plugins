package co.xenastudios.zchat.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then zChat's Kotlin entry point. */
public final class ZChatBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.zchat.bukkit.ZChatBukkit";
    }
}
