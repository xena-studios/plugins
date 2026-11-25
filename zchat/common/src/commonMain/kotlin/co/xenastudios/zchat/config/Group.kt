package co.xenastudios.zchat.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.permission.PermissionSet

/**
 * A chat format for players with a permission, under `formatting.groups`. [permission] is
 * `null` when the file leaves it out, meaning `zchat.group.<name>`; [format] blank means the
 * default format, so a group can exist for its weight and name alone.
 */
public class Group(
    public val weight: Int = 0,
    public val permission: String? = null,
    public val format: String = "",
) {
    /** The permission a player needs for [name]'s format. */
    public fun permission(name: String): String = permission ?: "zchat.group.$name"

    override fun equals(other: Any?): Boolean = other is Group && other.weight == weight && other.permission == permission && other.format == format
    override fun hashCode(): Int = (weight * 31 + permission.hashCode()) * 31 + format.hashCode()
    override fun toString(): String = "Group($weight, $permission, $format)"

    public companion object {
        private const val ARROW = " <dark_gray>»</dark_gray> <white><message></white>"

        /** The groups a new config has. */
        public val DEFAULTS: Map<String, Group> = linkedMapOf(
            "vip" to Group(10, "zchat.group.vip", "<gold>[VIP] <player></gold>$ARROW"),
            "staff" to Group(20, "zchat.group.staff", "<aqua>[Staff] <player></aqua>$ARROW"),
            "admin" to Group(30, "zchat.group.admin", "<red>[Admin] <player></red>$ARROW"),
        )

        private val NAME = Regex("[a-z0-9][a-z0-9_-]*")

        /**
         * What's wrong with the `formatting.groups` section the file has ([node]), one line each:
         * a group the type skipped says why here, since a type can only refuse the whole section.
         */
        public fun problems(node: YamlNode?): List<String> {
            if (node == null || node is YamlNode.Null) return emptyList()
            if (node !is YamlNode.Mapping) return listOf("formatting.groups should be a section of groups; using the examples.")
            return node.entries.mapNotNull { entry ->
                when (val read = read(entry.key, entry.value)) {
                    is Outcome.Ok -> null
                    is Outcome.Err -> "formatting.groups.${entry.key} (line ${entry.line}) ${read.error}; it's skipped."
                }
            }
        }

        private fun read(name: String, node: YamlNode): Outcome<Group, String> {
            if (!NAME.matches(name.lowercase())) return Outcome.Err("isn't a group name (letters, digits, - and _)")
            val section = node as? YamlNode.Mapping ?: return Outcome.Err("should be a section with a weight, a permission and a format")
            val weight = when (val raw = section["weight"]) {
                null, is YamlNode.Null -> 0
                is YamlNode.Scalar -> raw.content.trim().toIntOrNull() ?: return Outcome.Err("has weight \"${raw.content}\", which isn't a whole number")
                else -> return Outcome.Err("has a weight that should be a whole number")
            }
            val permission = when (val raw = section["permission"]) {
                null -> null
                is YamlNode.Null -> ""
                is YamlNode.Scalar -> raw.content.trim().lowercase().also {
                    if (it.isNotEmpty() && !PermissionSet.isNode(it)) return Outcome.Err("has permission \"$it\", which isn't a permission node")
                }
                else -> return Outcome.Err("has a permission that should be text")
            }
            val format = when (val raw = section["format"]) {
                null, is YamlNode.Null -> ""
                is YamlNode.Scalar -> raw.content
                else -> return Outcome.Err("has a format that should be text in quotes")
            }
            return Outcome.Ok(Group(weight, permission, format))
        }

        /** `formatting.groups`: groups by name. One that can't be read is skipped ([problems] says why). */
        public val TYPE: ConfigType<Map<String, Group>> = object : ConfigType<Map<String, Group>>() {
            override val name: String get() = "groups"
            override fun empty(): Map<String, Group> = emptyMap()

            override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, Group>, String> {
                if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of groups, each with a weight, a permission and a format")
                val out = LinkedHashMap<String, Group>()
                for (entry in node.entries) {
                    val read = read(entry.key, entry.value)
                    if (read is Outcome.Ok) out[entry.key.lowercase()] = read.value
                }
                return Outcome.Ok(out)
            }

            override fun encode(value: Map<String, Group>): YamlNode = YamlNode.Mapping(
                value.map { (name, group) ->
                    val entries = ArrayList<YamlNode.Mapping.Entry>()
                    entries += YamlNode.Mapping.Entry("weight", YamlNode.Scalar(group.weight.toString()))
                    group.permission?.let { entries += YamlNode.Mapping.Entry("permission", YamlNode.Scalar(it)) }
                    entries += YamlNode.Mapping.Entry("format", YamlNode.Scalar(group.format))
                    YamlNode.Mapping.Entry(name, YamlNode.Mapping(entries))
                },
            )
        }
    }
}
