package co.xenastudios.xmoney.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, which loads the Kotlin stdlib, then xMoney's Kotlin entry point. */
public final class XMoneyBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xmoney.bukkit.XMoneyBukkit";
    }
}
