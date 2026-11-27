# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), what the commands do with the first command (Actions), the
# punisher with the first punishment, the API's calls and a muted player's checks with their first
# use (Calls), what messages draw with the first message (TextsDrawingKt), the database's writes
# and lookups with the first join or command (WritesKt, LookupsKt), hashing IPs the day it's
# turned on (IpHashingKt), the Paper paths only once found (modern).
-keep,allowobfuscation class co.xenastudios.xbans.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xbans.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xbans.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xbans.Punisher { *; }
-keep,allowobfuscation class co.xenastudios.xbans.Network { *; }
-keep,allowobfuscation class co.xenastudios.xbans.bukkit.modern.** { *; }
-keep,allowobfuscation class co.xenastudios.xbans.ZChatMutes { *; }
-keep,allowobfuscation class co.xenastudios.xbans.FileImports$* { *; }
-keep,allowobfuscation class co.xenastudios.xbans.Calls { *; }
-keep,allowobfuscation class co.xenastudios.xbans.store.LookupsKt { *; }
-keep,allowobfuscation class co.xenastudios.xbans.store.IpHashingKt { *; }
-keep,allowobfuscation class co.xenastudios.xbans.TextsDrawingKt { *; }
-keep,allowobfuscation class co.xenastudios.xbans.store.WritesKt { *; }
