# The SFTP and FTP remotes: loaded by name, by a class loader of their own, with a library fetched
# when a remote needs it (remote.Libraries). Kept whole and in their package, which that loader
# picks them by, and never merged with a class outside it.
-keep class co.xenastudios.xbackup.remote.lib.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.commons.net.**
# Pinned, so R8 doesn't inline them back into what loads at startup (xbackup/bukkit/r8/xbackup.pro).
-keep,allowobfuscation class co.xenastudios.xbackup.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.fabric.FabricPause { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.store.Repository { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.backup.Backup { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.restore.Swap { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.remote.Mirror { *; }
