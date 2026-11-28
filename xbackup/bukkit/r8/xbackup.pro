# `java -jar xBackup.jar`: the manifest's Main-Class, and the Kotlin command line it calls by name.
-keep class co.xenastudios.xbackup.bukkit.XBackupCli { public static void main(java.lang.String[]); }
-keep class co.xenastudios.xbackup.cli.Cli { public static int main(java.lang.String[], java.nio.file.Path); }
# The SFTP and FTP remotes: loaded by name, by a class loader of their own, with a library fetched
# when a remote needs it (remote.Libraries). Kept whole and in their package, which that loader
# picks them by, and never merged with a class outside it.
-keep class co.xenastudios.xbackup.remote.lib.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.commons.net.**
# The API's Bukkit events are other plugins' to call: kept by name.
-keep class co.xenastudios.xbackup.api.bukkit.** { public *; }
# Pinned, so R8 doesn't inline them back into what loads at startup: what the commands do with the
# first command (Actions), the engine's work with the first job (the store, backups, restores,
# remotes), the Bukkit events with the first event (EventBridge$Fire), the world pause with the
# first backup.
-keep,allowobfuscation class co.xenastudios.xbackup.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.bukkit.BukkitPause { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.store.Repository { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.backup.Backup { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.restore.Swap { *; }
-keep,allowobfuscation class co.xenastudios.xbackup.remote.Mirror { *; }
