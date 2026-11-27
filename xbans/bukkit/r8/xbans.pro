# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), what the commands do with the first command (Actions), the
# punisher with the first punishment, the Paper paths only once found (modern).
-keep,allowobfuscation class co.xenastudios.xbans.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xbans.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xbans.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xbans.Punisher { *; }
-keep,allowobfuscation class co.xenastudios.xbans.Network { *; }
-keep,allowobfuscation class co.xenastudios.xbans.bukkit.modern.** { *; }
-keep,allowobfuscation class co.xenastudios.xbans.ZChatMutes { *; }
-keep,allowobfuscation class co.xenastudios.xbans.FileImports$* { *; }
