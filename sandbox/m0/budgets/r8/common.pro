# Shared by every plugin jar.
# Stack traces stay readable with the mapping file; generic signatures and annotations stay for
# the platform's reflection (Guice, Cloud's type tokens, @EventHandler, @Listener).
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions
-renamesourcefileattribute SourceFile
# A plugin's public API (a package named api) keeps its names and members for other plugins.
-keep public class co.xenastudios.**.api.** { public protected *; }
# Optional classes the stdlib refers to.
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
# Enums stay enums: libraries (Cloud, Guice, EnumMap/EnumSet) reach their constants by reflection,
# which R8's enum unboxing would break ("class ... not an enum").
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Type tokens (geantyref, which Cloud uses; Gson's and Guice's too) read their generic superclass:
# R8 keeps a Signature only on classes a rule keeps, so keep the subclasses (names may change).
-keep,allowobfuscation,allowshrinking class **.geantyref.TypeToken
-keep,allowobfuscation,allowshrinking class * extends **.geantyref.TypeToken
-keep,allowobfuscation,allowshrinking class **.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends **.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.inject.TypeLiteral
-keep,allowobfuscation,allowshrinking class * extends com.google.inject.TypeLiteral
