# Bukkit's ServicesManager registers the API under its interface, which the common rule keeps.
# The API's Bukkit helpers and events are other plugins' to call: kept by name.
-keep class co.xenastudios.xmarket.api.bukkit.** { public *; }
# Pinned whole: inlined into the entry point, it would load PlaceholderAPI's classes without it.
-keep class co.xenastudios.xmarket.bukkit.PapiHook { *; }
# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), what the commands do with the first command (Actions),
# the menus with the first menu (Ui), the importers and the audit with their first run, the
# Paper paths only once found (Modern), the legacy potions only before 1.13.
-keep,allowobfuscation class co.xenastudios.xmarket.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.Ui { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.FileImports { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.price.Audit { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.price.Pricing { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.bukkit.modern.** { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.bukkit.LegacyPotions { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.bukkit.VaultMoney { *; }
-keep,allowobfuscation class co.xenastudios.xmarket.XMoneyBridge { *; }
