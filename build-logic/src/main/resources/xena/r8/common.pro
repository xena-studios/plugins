# Every rewrite jar R8 shrinks (the xena.r8 convention), from what M0 learned on the spike
# jars (sandbox/m0/budgets/RESULTS.md). The task adds rules for the jar's own plugin.yml,
# fabric.mod.json and services, and -repackageclasses into the plugin's own package; a
# project's r8/*.pro come after this.

# Stack traces stay readable with the mapping file; generic signatures and annotations stay
# for the platform's reflection (Guice, Cloud's type tokens, @EventHandler, @Listener).
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions
-renamesourcefileattribute SourceFile

# A plugin's public API (a package named api) keeps its names and members for other
# plugins, and its Kotlin metadata for Kotlin callers.
-keep public class co.xenastudios.**.api.** { public protected *; }
-keep class kotlin.Metadata { *; }

# The core's loader makes a plugin's Kotlin entry point by name (BukkitBootstrap.entryPoint()),
# relocated into each plugin's package, and the platform makes the shim (plugin.yml's main).
-keep class * implements **.loader.PluginEntry { public <init>(...); }
-keep class * extends **.loader.bukkit.BukkitBootstrap { <init>(); }
-keep class * extends org.bukkit.plugin.java.JavaPlugin { <init>(); }
-keepclassmembers class * implements org.bukkit.event.Listener { @org.bukkit.event.EventHandler <methods>; }

# Enums stay enums: libraries (Cloud, Guice, EnumMap/EnumSet) reach their constants by
# reflection, which R8's enum unboxing would break ("class ... not an enum").
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

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
