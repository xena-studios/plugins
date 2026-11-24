# Every rewrite jar R8 shrinks (the xena.r8 convention), from what M0 learned on the spike
# jars (sandbox/m0/budgets/RESULTS.md). The task adds rules for the jar's own plugin.yml,
# fabric.mod.json and services, for the core types the plugin's API exposes, and
# -repackageclasses into the plugin's own package; a project's r8/*.pro come after this.
# R8 runs in full mode (its default), which already changes access where that helps.

# Stack traces stay readable with the mapping file kept for each release (retrace): line
# numbers stay, and every class's source file is one shared name, "SourceFile". Generic
# signatures and the annotations the JVM reads stay for the platform's reflection
# (@EventHandler, type tokens); the compile-time ones (@NotNull and the like) go.
-keepattributes SourceFile,LineNumberTable,Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Exceptions
-renamesourcefileattribute SourceFile

# A plugin's public API (a package named api, outside its relocated core) keeps its names
# and members for other plugins, and its Kotlin metadata for Kotlin callers. The core's own
# api packages, relocated into every plugin, aren't anyone's API but as far as the plugin's
# API exposes them: R8Shrink keeps exactly those types (PlayerRef, EventBus...), the rest is
# shrunk and renamed like any other class. Kotlin metadata stays only on what a rule keeps:
# nothing reads it at runtime (no kotlin-reflect).
-keep public class !**.core.**,co.xenastudios.**.api.** { public protected *; }
-keep class kotlin.Metadata { *; }

# The core's loader makes a plugin's Kotlin entry point by name (BukkitBootstrap.entryPoint()),
# relocated into each plugin's package, and the platform makes the shim (plugin.yml's main).
-keep class * implements **.loader.PluginEntry { public <init>(...); }
# The shim asks whether an entry point has a load() of its own, by name.
-keep interface **.loader.PluginEntry { *; }
-keepclassmembers class * implements **.loader.PluginEntry { public void load(); }
-keep class * extends **.loader.bukkit.BukkitBootstrap { <init>(); }
-keep class * extends org.bukkit.plugin.java.JavaPlugin { <init>(); }
-keepclassmembers class * implements org.bukkit.event.Listener { @org.bukkit.event.EventHandler <methods>; }
# Bukkit events keep their names, since other plugins and Skript listen to them by class, and
# their static getHandlerList(), which Bukkit finds by reflection when a listener registers.
-keep class * extends org.bukkit.event.Event {
    public static org.bukkit.event.HandlerList getHandlerList();
    public *;
}

# Enums: no rule. R8 unboxes an enum only when nothing can see it's an object (passed to
# EnumMap, a library or reflection, it stays one), and the rewrite reaches none by reflection.
# A plugin using a library that does (Cloud, Guice) keeps them in its own r8/*.pro:
#   -keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# Type tokens (geantyref, which Cloud uses; Gson's and Guice's too) read their generic
# superclass: R8 keeps a Signature only on classes a rule keeps, so keep the subclasses.
-keep,allowobfuscation,allowshrinking class **.geantyref.TypeToken
-keep,allowobfuscation,allowshrinking class * extends **.geantyref.TypeToken
-keep,allowobfuscation,allowshrinking class **.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends **.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.inject.TypeLiteral
-keep,allowobfuscation,allowshrinking class * extends com.google.inject.TypeLiteral

# Optional classes the stdlib and the server APIs refer to, which aren't library jars here.
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.intellij.lang.annotations.**
-dontwarn com.google.**
-dontwarn com.avaje.**
-dontwarn org.yaml.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.apiguardian.**
-dontwarn net.md_5.**
-dontwarn org.apache.commons.**
-dontwarn org.json.simple.**
