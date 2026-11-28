import org.gradle.api.provider.Property

/** What the `xena.fabric-mod` convention needs to know about a mod. */
abstract class XenaFabricModExtension {
    /**
     * The plugin's id, as in `fabric.mod.json`: `xutilities`. Its core moves to
     * `co.xenastudios.<id>.core`, and R8's renamed classes to the package `<id>`.
     */
    abstract val id: Property<String>

    /**
     * Whether the mod uses the core's world hooks (`FabricPlatform.world`, DECISIONS.md, "The
     * world hooks"): its jar then carries their mixins and their config, as `<id>-world.mixins.json`
     * in the mod's own package, which its `fabric.mod.json` lists under `mixins`. Off, the jar has
     * neither, and R8 drops the hooks.
     */
    abstract val worldHooks: Property<Boolean>
}
