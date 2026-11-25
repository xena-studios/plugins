import org.gradle.api.provider.Property

/** What the `xena.fabric-mod` convention needs to know about a mod. */
abstract class XenaFabricModExtension {
    /**
     * The plugin's id, as in `fabric.mod.json`: `xutilities`. Its core moves to
     * `co.xenastudios.<id>.core`, and R8's renamed classes to the package `<id>`.
     */
    abstract val id: Property<String>
}
