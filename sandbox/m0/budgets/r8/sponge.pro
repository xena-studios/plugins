# The Sponge plugin: Sponge finds the @Plugin class, builds it with Guice and calls @Listener methods.
-keep @org.spongepowered.plugin.builtin.jvm.Plugin class * { *; }
# Sponge reads a listener's generic event type (RegisterCommandEvent<Command.Raw>) from the
# method's local variable table, not its signature; without the table it fails "expected 1, but got 0".
-keepparameternames
-keepclassmembers class * {
    @org.spongepowered.api.event.Listener <methods>;
    @com.google.inject.Inject <init>(...);
    @com.google.inject.Inject <fields>;
}
-dontwarn co.xenastudios.spike.sponge.libs.**
-dontwarn org.checkerframework.**
-dontwarn org.apiguardian.**
-dontwarn javax.annotation.**
# Cloud's service pipeline keys services by type tokens such as TypeToken<CommandPreprocessor<C>>.
# R8 removes an interface nothing calls through and writes Object into those signatures, so two
# services clash ("Service of type 'Object' has already been registered"). Keep Cloud's
# interfaces; their names may still change.
-keep,allowobfuscation interface co.xenastudios.spike.sponge.libs.cloud.**
