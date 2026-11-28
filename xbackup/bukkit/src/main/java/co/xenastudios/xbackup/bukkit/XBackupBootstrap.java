package co.xenastudios.xbackup.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/**
 * The main class: the core's Java shim, which loads the Kotlin stdlib, then xBackup's Kotlin entry
 * point. Its onLoad runs before the server loads any world, which is when a staged restore is put
 * in place (XBackupBukkit.load).
 */
public final class XBackupBootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "co.xenastudios.xbackup.bukkit.XBackupBukkit";
    }
}
