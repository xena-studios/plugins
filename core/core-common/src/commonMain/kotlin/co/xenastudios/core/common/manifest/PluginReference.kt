package co.xenastudios.core.common.manifest

import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.placeholder.PlaceholderSet

/**
 * Everything a plugin declares that its docs describe: the same objects it runs on, gathered
 * for the manifest ([Manifest.write]). A plugin builds one the way its tests build the plugin,
 * on the fake platform, and names it to the build through a [ManifestSource].
 *
 * Checked when it's made: every permission a command or hook uses is declared in [permissions],
 * no node or name is declared twice, and everything belongs to plugin [id].
 */
public class PluginReference(
    /** Lower-case id: its folder, its docs path, its permissions' prefix: `xhomes`. */
    public val id: String,
    /** Its name, as people read it: `xHomes`. */
    public val name: String,
    /** One line on what it does. */
    public val description: String,
    /** Its commands, the admin subcommands among them. */
    public val commands: CommandTree,
    /** Its permissions: its own, and the admin ones ([co.xenastudios.core.common.command.AdminCommands.permissions]). */
    public val permissions: List<PermissionSet>,
    /** Its config, if it has one. */
    public val config: ConfigSchema? = null,
    /** Its placeholders, if it has any. */
    public val placeholders: PlaceholderSet? = null,
    /** Its messages, as the translations declare them; see [MessageInfo]. */
    public val messages: List<MessageInfo> = emptyList(),
    /** Its API's events; see [EventInfo]. */
    public val events: List<EventInfo> = emptyList(),
) {
    init {
        val declared = HashSet<String>()
        for (set in permissions) {
            require(set.pluginId == id) { "permissions of ${set.pluginId} in $id's reference" }
            for (permission in set.permissions) require(declared.add(permission.node)) { "permission ${permission.node} declared twice" }
        }
        val all = permissions.flatMap { it.permissions }
        for (node in commands.nodes) {
            val needed = listOfNotNull(node.permission) + node.hooks.mapNotNull { it.bypass }
            for (permission in needed) require(all.any { it === permission }) { "$node uses ${permission.node}, which $id doesn't declare" }
        }
        for (root in commands.roots) {
            for (other in commands.roots) require(root === other || root.name !in other.aliases) { "/${root.name} is also an alias of $other" }
        }
        require(placeholders == null || placeholders.pluginId == id) { "placeholders of ${placeholders?.pluginId} in $id's reference" }
        require(messages.map { it.key }.toSet().size == messages.size) { "a message is listed twice" }
        require(events.map { it.key }.toSet().size == events.size) { "an event is listed twice" }
    }
}

/**
 * Names a plugin's [PluginReference] to the build: an `object` the `xena.manifest` convention's
 * `xenaReference { source = "..." }` points at, loaded on the JVM by name when the manifest is
 * written. It may live in the plugin's tests, beside the fake platform it builds the plugin on.
 */
public interface ManifestSource {
    /** The reference to write. */
    public val reference: PluginReference
}

/**
 * One message, as the manifest lists it: its key, English text, comment and placeholders.
 * The translations' `MessageBundle` gives these for its messages; until it lands, a plugin
 * lists none.
 */
public data class MessageInfo(
    public val key: String,
    public val english: String,
    public val comment: List<String>,
    public val placeholders: List<String>,
)

/**
 * One event of a plugin's API, as the manifest lists it: its [key] (the `EventType`'s, such as
 * `xmoney:balance_changed`), the class developers listen for, and whether it can be cancelled.
 * Listed by hand until the API's events are read from its code (DECISIONS.md, M0).
 */
public data class EventInfo(
    public val key: String,
    public val className: String,
    public val description: String,
    public val cancellable: Boolean,
    public val since: String? = null,
)

/** One platform a plugin runs on, or will, from its build. */
public data class PlatformInfo(
    /** Stable id: `paper`. */
    public val id: String,
    /** As people read it: `Paper`. */
    public val name: String,
    public val kind: Kind,
    /** The oldest Minecraft (or proxy or API) version supported. */
    public val minVersion: String,
    /** The newest, or `latest`. */
    public val maxVersion: String,
    /** The Java the server needs at least, or `null` where Java doesn't apply. */
    public val java: Int?,
    public val status: Status,
    /** The plugin version that added it. */
    public val since: String?,
) {
    /** What runs it. */
    public enum class Kind(public val id: String) { SERVER("server"), PROXY("proxy"), LIBRARY("library") }

    /** Whether it's out, or on the roadmap. */
    public enum class Status(public val id: String) { RELEASE("release"), PLANNED("planned") }

    /** The line the build passes for it: `id;name;kind;min;max;java;status;since`. */
    public fun line(): String = listOf(id, name, kind.id, minVersion, maxVersion, java?.toString().orEmpty(), status.id, since.orEmpty()).joinToString(";")

    public companion object {
        /** Reads a [line]. */
        public fun parse(line: String): PlatformInfo {
            val f = line.split(';')
            require(f.size == 8) { "a platform is id;name;kind;min;max;java;status;since, not $line" }
            return PlatformInfo(
                id = f[0], name = f[1],
                kind = Kind.entries.firstOrNull { it.id == f[2] } ?: throw IllegalArgumentException("platform kind ${f[2]}: server, proxy or library"),
                minVersion = f[3], maxVersion = f[4],
                java = f[5].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Java ${f[5]} isn't a number") },
                status = Status.entries.firstOrNull { it.id == f[6] } ?: throw IllegalArgumentException("platform status ${f[6]}: release or planned"),
                since = f[7].ifEmpty { null },
            )
        }
    }
}
