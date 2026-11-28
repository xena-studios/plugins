import org.gradle.api.provider.Property

/** What the `xena.sponge-plugin` convention needs to know about a plugin. */
abstract class XenaSpongePluginExtension {
    /**
     * The plugin's id, as in `META-INF/sponge_plugins.json`: `xutilities`. Its core moves to
     * `co.xenastudios.<id>.core`, the libraries it carries to `co.xenastudios.<id>.libs`, and R8's
     * renamed classes to the package `<id>`.
     */
    abstract val id: Property<String>
}
