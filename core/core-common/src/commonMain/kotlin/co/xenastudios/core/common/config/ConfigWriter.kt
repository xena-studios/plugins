package co.xenastudios.core.common.config

/**
 * Writes a config file the way a person would: the schema's order and comments, strings in
 * double quotes, short lists inline, a blank line above each top-level setting and each
 * commented section. Values come from the file's own settings where it has them (the owner's,
 * even ones that can't be used, so nothing they wrote is lost) and from the defaults where it
 * doesn't. Keys the schema doesn't know are kept, after the known ones of their section.
 *
 * Writing, not reading, so it needs no YAML library and runs on every target.
 */
internal object ConfigWriter {

    private const val INDENT = "  "
    private const val FLOW_WIDTH = 100
    private const val VERSION_COMMENT = "Managed by the plugin, to carry your settings forward on updates. Don't edit it."

    private val PLAIN_KEY = Regex("[A-Za-z0-9_][A-Za-z0-9_-]*")
    private val NUMBER = Regex("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?")
    private val BOOLEAN = setOf("true", "false", "yes", "no", "on", "off")
    private val NULLS = setOf("null", "~")

    fun write(schema: ConfigSchema, root: YamlNode.Mapping?, version: Int): String {
        val out = StringBuilder()
        for (line in schema.header) comment(out, 0, line)
        if (schema.header.isNotEmpty()) out.append('\n')
        comment(out, 0, VERSION_COMMENT)
        out.append(ConfigGroup.VERSION_KEY).append(": ").append(version).append('\n')
        group(out, schema, root, 0)
        return out.toString()
    }

    private fun group(out: StringBuilder, group: ConfigGroup, raw: YamlNode.Mapping?, depth: Int) {
        for (child in group.children) {
            val value = raw?.get(child.name)
            if (depth == 0 || (child is ConfigSection && child.comment.isNotEmpty())) out.append('\n')
            for (line in child.comment) comment(out, depth, line)
            when (child) {
                is ConfigKey<*> -> key(out, child, value, depth)
                is ConfigSection -> when {
                    value == null || value is YamlNode.Null || value is YamlNode.Mapping -> {
                        indent(out, depth).append(key(child.name)).append(if (child.children.isEmpty()) ": {}\n" else ":\n")
                        group(out, child, value as? YamlNode.Mapping, depth + 1)
                    }
                    // The owner's value where a section belongs: kept, and reported each load.
                    else -> entry(out, child.name, value, depth, null)
                }
            }
        }
        val known = group.children.mapTo(HashSet()) { it.name }
        for (extra in raw?.entries.orEmpty()) {
            if (extra.key in known || (depth == 0 && extra.key == ConfigGroup.VERSION_KEY)) continue
            if (depth == 0) out.append('\n')
            entry(out, extra.key, extra.value, depth, null)
        }
    }

    private fun <T : Any> key(out: StringBuilder, key: ConfigKey<T>, raw: YamlNode?, depth: Int) {
        val empty = key.type.empty()
        val node = when {
            raw == null -> key.type.encode(key.default)
            // `permission:` means blank: written as "" or [] so it reads the same everywhere.
            raw is YamlNode.Null && empty != null -> key.type.encode(empty)
            else -> raw
        }
        val type = key.type
        val plain = if (type is ConfigType.ListType<*>) type.element.plain else type.plain
        entry(out, key.name, node, depth, plain)
    }

    /** `key: value`, where [plain] says how its scalars are written: bare if they can be, quoted, or (`null`) by their look. */
    private fun entry(out: StringBuilder, name: String, node: YamlNode, depth: Int, plain: Boolean?) {
        indent(out, depth).append(key(name)).append(':')
        when (node) {
            is YamlNode.Null -> out.append('\n')
            is YamlNode.Scalar -> out.append(' ').append(scalar(node.content, plain)).append('\n')
            is YamlNode.Sequence -> {
                val inline = inline(node, plain)
                if (node.items.all { it is YamlNode.Scalar } && depth * 2 + name.length + inline.length <= FLOW_WIDTH) {
                    out.append(' ').append(inline).append('\n')
                } else {
                    out.append('\n')
                    for (item in node.items) {
                        indent(out, depth + 1).append("- ").append(if (item is YamlNode.Scalar) scalar(item.content, plain) else inline(item, null)).append('\n')
                    }
                }
            }
            is YamlNode.Mapping -> {
                if (node.entries.isEmpty()) {
                    out.append(" {}\n")
                } else {
                    out.append('\n')
                    for (child in node.entries) entry(out, child.key, child.value, depth + 1, null)
                }
            }
        }
    }

    /** [node] on one line, in YAML's flow style: `"a"`, `[1, 2]`, `{a: "b"}`. */
    fun inline(node: YamlNode, plain: Boolean?): String = when (node) {
        is YamlNode.Null -> "null"
        is YamlNode.Scalar -> scalar(node.content, plain)
        is YamlNode.Sequence -> node.items.joinToString(", ", "[", "]") { inline(it, plain) }
        is YamlNode.Mapping -> node.entries.joinToString(", ", "{", "}") { key(it.key) + ": " + inline(it.value, null) }
    }

    private fun scalar(content: String, plain: Boolean?): String {
        val looksPlain = NUMBER.matches(content) || content.lowercase() in BOOLEAN
        return if (plain != false && looksPlain) content else quote(content)
    }

    private fun key(name: String): String =
        if (PLAIN_KEY.matches(name) && name.lowercase() !in NULLS && name.lowercase() !in BOOLEAN) name else quote(name)

    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\t' -> out.append("\\t")
                c == '\r' -> out.append("\\r")
                c.code < 0x20 || c.code == 0x7F -> out.append("\\x").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private const val HEX = "0123456789abcdef"

    private fun comment(out: StringBuilder, depth: Int, line: String) {
        indent(out, depth).append(if (line.isEmpty()) "#" else "# $line").append('\n')
    }

    private fun indent(out: StringBuilder, depth: Int): StringBuilder {
        repeat(depth) { out.append(INDENT) }
        return out
    }
}
