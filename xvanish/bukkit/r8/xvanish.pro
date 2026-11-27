# PlaceholderAPI calls the expansion's methods by overriding; Bukkit's ServicesManager registers
# the API under its interface, which the common rule keeps.
-keep class co.xenastudios.xvanish.bukkit.Papi { public *; }
# Pinned whole: inlined into the entry point, it would load PlaceholderAPI's classes without it.
-keep class co.xenastudios.xvanish.bukkit.PapiHook { *; }
# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), the admin parts with their first use, network mode only
# with network.enabled, the importers with /xvanish import, the Paper paths only once found.
-keep,allowobfuscation class co.xenastudios.xvanish.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xvanish.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xvanish.Network { *; }
-keep,allowobfuscation class co.xenastudios.xvanish.JvmNetwork { *; }
-keep,allowobfuscation class co.xenastudios.xvanish.VanishImporter { *; }
-keep,allowobfuscation class co.xenastudios.xvanish.bukkit.modern.** { *; }
