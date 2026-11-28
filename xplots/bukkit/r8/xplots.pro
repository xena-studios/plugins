# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first change a listener hears (EventBridge$Fire), what the commands do with the first command
# (Actions), the debug report and forget with their first use (AdminParts), the API's changes with
# the first plugin to call one (Api), the menus with the first menu (Ui), making and deleting fields
# with the first (Builder), snapshots and resets with the first field or reset (Resets), the warmups
# with the first teleport (Teleports), the placeholders with PlaceholderAPI's first ask, the family's
# APIs with the first payment, rollback, picker or homes (Bridges), WorldGuard and WorldEdit only
# when installed (EngineHub), and the Paper paths only once found (modern).
-keep,allowobfuscation class co.xenastudios.xplots.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xplots.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xplots.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xplots.Api { *; }
-keep,allowobfuscation class co.xenastudios.xplots.Ui { *; }
-keep,allowobfuscation class co.xenastudios.xplots.Builder { *; }
-keep,allowobfuscation class co.xenastudios.xplots.reset.Resets { *; }
-keep,allowobfuscation class co.xenastudios.xplots.reset.Snapshot { *; }
-keep,allowobfuscation class co.xenastudios.xplots.Teleports { *; }
-keep,allowobfuscation class co.xenastudios.xplots.Placeholders { *; }
-keep,allowobfuscation class co.xenastudios.xplots.XMoneyBridge { *; }
-keep,allowobfuscation class co.xenastudios.xplots.XTeleportHomes { *; }
-keep,allowobfuscation class co.xenastudios.xplots.XRewindBridge { *; }
-keep,allowobfuscation class co.xenastudios.xplots.XVanishBridge { *; }
-keep,allowobfuscation class co.xenastudios.xplots.JvmDeflate { *; }
-keep,allowobfuscation class co.xenastudios.xplots.bukkit.VaultMoney { *; }
-keep,allowobfuscation class co.xenastudios.xplots.bukkit.WorldGuardHook { *; }
-keep,allowobfuscation class co.xenastudios.xplots.bukkit.WorldEditHook { *; }
-keep,allowobfuscation class co.xenastudios.xplots.bukkit.modern.** { *; }
