package co.xenastudios.core.common.manifest

import co.xenastudios.core.api.event.Cancellable
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.placeholder.PlaceholderSet
import kotlin.jvm.JvmName
import kotlin.reflect.KClass

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
    /**
     * Its messages: the catalog its `lang/` files hold, the core's among them, if it sends any.
     * The manifest lists them ([messages]), and the build's `checkTranslations` checks each
     * shipped translation against them.
     */
    public val catalog: MessageCatalog? = null,
    /** Its API's events: [EventInfo.of] each event type. */
    public val events: List<EventInfo> = emptyList(),
) {
    /** Its messages, as the manifest lists them. */
    public val messages: List<MessageInfo> = catalog?.let(MessageInfo::of).orEmpty()

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
 * One message, as the manifest lists it: its key, English text, comment and placeholders, read
 * from the plugin's [MessageCatalog] with [of], so the docs show what the plugin sends.
 */
public data class MessageInfo(
    public val key: String,
    public val english: String,
    public val comment: List<String>,
    public val placeholders: List<String>,
) {
    public companion object {
        /** Every message of [catalog], in its files' order: the core's and the plugin's. */
        public fun of(catalog: MessageCatalog): List<MessageInfo> =
            catalog.messages.map { MessageInfo(it.key, it.english, it.comment, it.placeholders) }
    }
}

/**
 * One event of a plugin's API, as the manifest lists it: its [key] (the `EventType`'s, such as
 * `xmoney:balance_changed`), the class developers listen for, and whether it can be cancelled.
 * Read from the API's own event types with [of], so the key, the class and whether it's
 * cancellable can't disagree with the code; the [description] and [since] are the docs' own.
 */
public data class EventInfo(
    public val key: String,
    public val className: String,
    public val description: String,
    public val cancellable: Boolean,
    public val since: String? = null,
) {
    public companion object {
        /** [type]'s event, a pre-event listeners can cancel ([Cancellable]). */
        @JvmName("ofCancellable")
        public inline fun <reified E : Cancellable> of(type: EventType<E>, description: String, since: String? = null): EventInfo =
            EventInfo(type.name, className(E::class), description, cancellable = true, since)

        /** [type]'s event, which listeners hear but can't stop. */
        public inline fun <reified E : Event> of(type: EventType<E>, description: String, since: String? = null): EventInfo =
            EventInfo(type.name, className(E::class), description, cancellable = false, since)

        /** The name developers import [type] by: its full name, where the platform knows it. */
        @PublishedApi
        internal fun className(type: KClass<*>): String = type.qualifiedName ?: type.simpleName ?: "?"
    }
}

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
