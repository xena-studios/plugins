# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), the admin parts and importers with their first use, each
# switch's listener only while its limit is on, the Paper paths only once found.
-keep,allowobfuscation class co.xenastudios.xlimits.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xlimits.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xlimits.LimitImporter { *; }
-keep,allowobfuscation class co.xenastudios.xlimits.FarmControl { *; }
-keep,allowobfuscation class co.xenastudios.xlimits.Insights { *; }
-keep,allowobfuscation class co.xenastudios.xlimits.bukkit.modern.** { *; }
