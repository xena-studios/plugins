# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first lookup or rollback a listener hears (EventBridge$Fire), what the commands do with the
# first command (Actions), the debug report and forget with their first use (AdminParts), the API's
# calls with the first plugin to call one (Api), lookups with the first lookup, rollbacks' planning
# and applying with the first rollback, the CoreProtect import and purges when they run, the Paper
# paths only once found (modern).
-keep,allowobfuscation class co.xenastudios.xrewind.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.Api { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.Lookups { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.store.Lookup { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.store.Purge { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.rollback.Planner { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.rollback.Applier { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.CoreProtectImport$* { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.bukkit.modern.** { *; }
# What a change is logged as (Changes), with the first change; the rows it's written as (Rows), with
# the first batch; blocks, contents and items on Bukkit (BukkitBlocks), with the first break or
# rollback; the inspector's listeners (Inspection), with the first inspector.
-keep,allowobfuscation class co.xenastudios.xrewind.log.Changes { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.log.Rows { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.log.Dictionaries { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.bukkit.BukkitBlocks { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.bukkit.Inspection { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.rollback.Rollbacks { *; }
-keep,allowobfuscation class co.xenastudios.xrewind.log.Batches { *; }
