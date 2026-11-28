import org.gradle.api.provider.Property

/** What the `xena.neoforge-mod` convention needs to know about a mod. */
abstract class XenaNeoForgeModExtension {
    /**
     * The plugin's id, as in `neoforge.mods.toml`: `xutilities`. Its core moves to
     * `co.xenastudios.<id>.core`, and R8's renamed classes to the package `<id>`.
     */
    abstract val id: Property<String>
}
