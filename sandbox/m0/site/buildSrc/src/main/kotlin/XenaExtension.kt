import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** What the build knows about a plugin that its code doesn't: its id, versions and platforms. */
abstract class XenaExtension {
    abstract val pluginId: Property<String>

    /** The Kotlin `object` that declares the plugin (a `PluginDefinitionProvider`). */
    abstract val definition: Property<String>

    /** `docs/src/generated`: where the committed manifests live. */
    abstract val docsDir: DirectoryProperty

    /** One line per platform, `id;name;kind;min;max;java;status;since`, passed to the writer. */
    abstract val platforms: ListProperty<String>

    fun platform(
        id: String,
        name: String,
        min: String,
        max: String,
        java: Int?,
        status: String,
        since: String? = null,
        kind: String = "server",
    ) {
        platforms.add(listOf(id, name, kind, min, max, java?.toString() ?: "", status, since ?: "").joinToString(";"))
    }
}
