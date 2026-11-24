package co.xenastudios.xutilities.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.permission.PermissionSet

/**
 * A command an owner defines under `info-commands`, which sends its [message]: usually a link.
 * [permission] is `null` when the file leaves it out, meaning `xutilities.command.<name>`.
 */
public class LinkCommand(
    public val enabled: Boolean = true,
    public val aliases: List<String> = emptyList(),
    public val permission: String? = null,
    /** The message, in the markup the lang files use; the theme's tags work in it. */
    public val message: String = "",
) {
    override fun equals(other: Any?): Boolean = other is LinkCommand && other.enabled == enabled &&
        other.aliases == aliases && other.permission == permission && other.message == message

    override fun hashCode(): Int = ((enabled.hashCode() * 31 + aliases.hashCode()) * 31 + permission.hashCode()) * 31 + message.hashCode()

    override fun toString(): String = "LinkCommand($enabled, $aliases, $permission, $message)"

    public companion object {
        /** The examples a new config has. */
        public val DEFAULTS: Map<String, LinkCommand> = linkedMapOf(
            "discord" to example("discord", "Join our Discord: ", "https://discord.gg/example", "discord.gg/example"),
            "store" to example("store", "Visit our store: ", "https://store.example.com", "store.example.com"),
            "website" to example("website", "Visit our website: ", "https://example.com", "example.com"),
        )

        private fun example(name: String, text: String, url: String, shown: String) = LinkCommand(
            permission = "xutilities.command.$name",
            message = "<prefix>$text<click:open_url:'$url'><hover:show_text:'<gray>Click to open'><highlight><underlined>$shown",
        )

        /**
         * What's wrong with the `info-commands` section the file has ([node]), one line each: a
         * link the type skipped says why here, since a type can only refuse the whole section.
         */
        public fun problems(node: YamlNode?): List<String> {
            if (node == null || node is YamlNode.Null) return emptyList()
            if (node !is YamlNode.Mapping) return listOf("info-commands should be a section of commands; using the examples.")
            val out = ArrayList<String>()
            for (entry in node.entries) {
                val reason = when (val read = read(entry.key, entry.value)) {
                    is Outcome.Ok -> null
                    is Outcome.Err -> read.error
                } ?: continue
                out += "info-commands.${entry.key} (line ${entry.line}) $reason; it's skipped."
            }
            return out
        }

        private val NAME = Regex("[a-z0-9][a-z0-9_-]*")

        private fun read(name: String, node: YamlNode): Outcome<LinkCommand, String> {
            if (!NAME.matches(name.lowercase())) return Outcome.Err("isn't a command name (letters, digits, - and _)")
            val section = when (node) {
                is YamlNode.Mapping -> node
                // `vote:` alone, or `vote: "<message>"`: a message and nothing else.
                is YamlNode.Scalar -> return Outcome.Ok(LinkCommand(message = node.content))
                else -> return Outcome.Err("should be a section with a message")
            }
            val enabled = when (val raw = (section["enabled"] as? YamlNode.Scalar)?.content?.trim()?.lowercase()) {
                null, "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> return Outcome.Err("has enabled: $raw, which should be true or false")
            }
            val aliases = when (val raw = section["aliases"]) {
                null, is YamlNode.Null -> emptyList()
                is YamlNode.Scalar -> listOf(raw.content)
                is YamlNode.Sequence -> raw.items.mapNotNull { (it as? YamlNode.Scalar)?.content }
                is YamlNode.Mapping -> return Outcome.Err("has aliases that should be a list")
            }.map { it.trim().lowercase().removePrefix("/") }.filter { it.isNotEmpty() }
            for (alias in aliases) if (!NAME.matches(alias)) return Outcome.Err("has an alias, $alias, that isn't a command name")
            val permission = when (val raw = section["permission"]) {
                null -> null
                is YamlNode.Null -> ""
                is YamlNode.Scalar -> raw.content.trim().lowercase().also {
                    if (it.isNotEmpty() && !PermissionSet.isNode(it)) return Outcome.Err("has permission \"$it\", which isn't a permission node")
                }
                else -> return Outcome.Err("has a permission that should be text")
            }
            val message = when (val raw = section["message"]) {
                is YamlNode.Scalar -> raw.content
                is YamlNode.Null -> ""
                null -> return Outcome.Err("has no message")
                else -> return Outcome.Err("has a message that should be text in quotes")
            }
            return Outcome.Ok(LinkCommand(enabled, aliases, permission, message))
        }

        /** `info-commands` in the config: a section of link commands by name. One that can't be read is skipped ([problems] says why). */
        public val TYPE: ConfigType<Map<String, LinkCommand>> = object : ConfigType<Map<String, LinkCommand>>() {
            override val name: String get() = "link commands"
            override fun empty(): Map<String, LinkCommand> = emptyMap()

            override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, LinkCommand>, String> {
                if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of commands, each with a message")
                val out = LinkedHashMap<String, LinkCommand>()
                for (entry in node.entries) {
                    val read = read(entry.key, entry.value)
                    if (read is Outcome.Ok) out[entry.key.lowercase()] = read.value
                }
                return Outcome.Ok(out)
            }

            override fun encode(value: Map<String, LinkCommand>): YamlNode = YamlNode.Mapping(
                value.map { (name, link) ->
                    val entries = ArrayList<YamlNode.Mapping.Entry>()
                    entries += YamlNode.Mapping.Entry("enabled", YamlNode.Scalar(link.enabled.toString()))
                    entries += YamlNode.Mapping.Entry("aliases", YamlNode.Sequence(link.aliases.map { YamlNode.Scalar(it) }))
                    link.permission?.let { entries += YamlNode.Mapping.Entry("permission", YamlNode.Scalar(it)) }
                    entries += YamlNode.Mapping.Entry("message", YamlNode.Scalar(link.message))
                    YamlNode.Mapping.Entry(name, YamlNode.Mapping(entries))
                },
            )
        }
    }
}
