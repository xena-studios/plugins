@file:JvmName("ManifestMain")

package co.xenastudios.core.common.manifest

import co.xenastudios.core.common.config.Configs
import java.io.File

/**
 * What the build's `xenaManifest` task runs (the `xena.manifest` convention in build-logic), on
 * the JVM, with the plugin's classes: no server, no platform. Writes
 *
 * - `<out>/<id>/manifest.json`, the plugin's reference;
 * - `<out>/<id>/config.yml`, its default config, if it has one, as the docs show it;
 * - `<out>/manifest.schema.json`, the schema every manifest follows.
 *
 * Arguments: `--source <class>` (a [ManifestSource] `object`), `--version <version>`,
 * `--out <folder>`, and `--platform <line>` for each platform ([PlatformInfo.line]).
 */
public fun main(args: Array<String>) {
    val options = args.toList().chunked(2).onEach { require(it.size == 2) { "${it[0]} needs a value" } }.groupBy({ it[0] }, { it[1] })
    fun one(name: String) = options[name]?.singleOrNull() ?: throw IllegalArgumentException("$name is needed, once")

    // The one reflective call, at build time only: the object the build names.
    val source = Class.forName(one("--source")).getField("INSTANCE").get(null) as? ManifestSource
        ?: throw IllegalArgumentException("${one("--source")} isn't an object implementing ManifestSource")
    val reference = source.reference
    val platforms = options["--platform"].orEmpty().map(PlatformInfo::parse)

    val out = File(one("--out"))
    val folder = File(out, reference.id).apply { mkdirs() }
    File(folder, "manifest.json").writeText(Manifest.write(reference, one("--version"), platforms))
    reference.config?.let { File(folder, "config.yml").writeText(Configs.defaultText(it)) }
    File(out, Manifest.SCHEMA_FILE).writeText(Manifest.schema())

    val commands = reference.commands.nodes.count { it.executable }
    val permissions = reference.permissions.sumOf { it.permissions.size }
    println("Wrote ${reference.id}'s manifest: $commands commands, $permissions permissions, ${reference.placeholders?.placeholders?.size ?: 0} placeholders")
}
