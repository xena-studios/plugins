# PlaceholderAPI calls the expansion's methods by overriding; Bukkit's ServicesManager registers
# the API under its interface, which the common rule keeps.
-keep class co.xenastudios.xteleport.bukkit.Papi { public *; }
# Pinned whole: inlined into the entry point, it would load PlaceholderAPI's classes without it.
-keep class co.xenastudios.xteleport.bukkit.PapiHook { *; }
# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), what the commands do with the first command (Actions), the
# teleports with the first teleport, the Paper paths only once found (Modern).
-keep,allowobfuscation class co.xenastudios.xteleport.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xteleport.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xteleport.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xteleport.teleport.Teleporter { *; }
-keep,allowobfuscation class co.xenastudios.xteleport.rtp.Rtp { *; }
-keep,allowobfuscation class co.xenastudios.xteleport.bukkit.modern.** { *; }
-keep,allowobfuscation class co.xenastudios.xteleport.bukkit.VaultEconomy { *; }
-keep,allowobfuscation class co.xenastudios.xteleport.XMoneyEconomy { *; }
