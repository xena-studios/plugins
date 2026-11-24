import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * What the `xena.manifest` convention needs to write a plugin's reference manifest: what its
 * code doesn't say. The code says the rest (commands, permissions, config, placeholders,
 * messages, events), through its `ManifestSource`.
 */
abstract class XenaReferenceExtension {
    /** The plugin's id, which names its folder in `docs/src/generated/`: `xhomes`. */
    abstract val pluginId: Property<String>

    /**
     * The `ManifestSource` object to write, by class name: `co.xenastudios.xhomes.HomesReference`.
     * Looked up on the JVM test runtime classpath, so it can build the plugin on core-testing's
     * fake platform.
     */
    abstract val source: Property<String>

    /** Where the committed manifests live: `docs/src/generated/` unless set. */
    abstract val docsDir: DirectoryProperty

    /** The platforms, one line each (`id;name;kind;min;max;java;status;since`), from [platform]. */
    abstract val platforms: ListProperty<String>

    /**
     * A platform the plugin runs on ([status] `release`) or will (`planned`): the docs' platform
     * table. [kind] is `server`, `proxy` or `library`; [java] is `null` where Java doesn't apply.
     */
    fun platform(
        id: String,
        name: String,
        min: String,
        max: String = "latest",
        java: Int? = null,
        status: String = "release",
        since: String? = null,
        kind: String = "server",
    ) {
        require(';' !in id + name + min + max + (since ?: "")) { "a platform's fields can't hold ;" }
        platforms.add(listOf(id, name, kind, min, max, java?.toString().orEmpty(), status, since.orEmpty()).joinToString(";"))
    }
}
