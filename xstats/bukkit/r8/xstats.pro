# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), what the commands do with the first command (Actions), the
# admin parts with the first /xstats debug or forget, the website only while web.enabled is on
# (JvmExtras and the web package), the import with the first import, the family's hooks once the
# database is open, the placeholders only while PlaceholderAPI is on, boards only on servers with
# text displays.
-keep,allowobfuscation class co.xenastudios.xstats.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xstats.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xstats.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xstats.StatsFiles { *; }
-keep,allowobfuscation class co.xenastudios.xstats.MiniJson { *; }
-keep,allowobfuscation class co.xenastudios.xstats.FamilyHooks { *; }
-keep,allowobfuscation class co.xenastudios.xstats.FamilyHooks$Counter { *; }
-keep,allowobfuscation class co.xenastudios.xstats.web.WebServer { *; }
-keep,allowobfuscation class co.xenastudios.xstats.web.Pages { *; }
-keep,allowobfuscation class co.xenastudios.xstats.web.Heads { *; }
-keep,allowobfuscation class co.xenastudios.xstats.bukkit.modern.TextBoards { *; }
-keep,allowobfuscation class co.xenastudios.xstats.bukkit.modern.Criteria { *; }
-keep class co.xenastudios.xstats.bukkit.Placeholders { *; }
-keep,allowobfuscation class co.xenastudios.xstats.bukkit.PlaceholderHook { *; }
-keep,allowobfuscation class co.xenastudios.xstats.bukkit.Fill { *; }
