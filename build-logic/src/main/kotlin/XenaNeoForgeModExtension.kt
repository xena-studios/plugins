import org.gradle.api.provider.Property

/** What the `xena.neoforge-mod` convention needs to know about a mod. */
abstract class XenaNeoForgeModExtension {
    /**
     * The plugin's id, as in `neoforge.mods.toml`: `xutilities`. Its core moves to
     * `co.xenastudios.<id>.core`, and R8's renamed classes to the package `<id>`.
     */
    abstract val id: Property<String>

    /**
     * Whether the mod uses the core's world hooks (`NeoForgePlatform.world`, NeoForge only): its
     * jar then carries their mixins and their config, as `<id>-world.mixins.json` in the mod's own
     * package, which its `neoforge.mods.toml` lists in a `[[mixins]]` table. Off, the jar has
     * neither, and R8 drops the hooks.
     */
    abstract val worldHooks: Property<Boolean>
}
