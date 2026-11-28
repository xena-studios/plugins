# The bootstrap asks for the generator by name (getDefaultWorldGenerator, before the plugin's
# enabled); a generator left by a reloaded-in-place xLimbo is read by its class name and floorY().
-keep class co.xenastudios.xlimbo.bukkit.Generators { public static *** forWorld(...); }
-keep class co.xenastudios.xlimbo.bukkit.VoidGenerator { public int floorY(); }
# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), the admin parts with their first use, the family's bridges
# once xTeleport or xVanish is there.
-keep,allowobfuscation class co.xenastudios.xlimbo.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xlimbo.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xlimbo.bukkit.XTeleportBridge { *; }
-keep,allowobfuscation class co.xenastudios.xlimbo.bukkit.XVanishBridge { *; }
