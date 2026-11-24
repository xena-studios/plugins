package co.xenastudios.m0.site.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * config.yml as typed keys, declared in file order. The plugin reads `spec.startingCoins`
 * (typed, with its default); the docs read the same keys. Subclass it as an `object`.
 */
open class ConfigSpec {
    internal val nodes = mutableListOf<Node>()

    sealed class Node(val path: String, val comment: List<String>) {
        val depth get() = path.count { it == '.' }
        val key get() = path.substringAfterLast('.')
    }

    class Section internal constructor(path: String, comment: List<String>) : Node(path, comment)

    class Key<T : Any> internal constructor(
        path: String,
        comment: List<String>,
        val type: ConfigType,
        val default: T,
        val reloadable: Boolean,
        val choices: List<String> = emptyList(),
        val min: Double? = null,
        val max: Double? = null,
    ) : Node(path, comment) {
        fun json(): JsonElement = when (val d = default) {
            is List<*> -> JsonArray(d.map { JsonPrimitive(it as String) })
            is Boolean -> JsonPrimitive(d)
            is Number -> JsonPrimitive(d)
            else -> JsonPrimitive(d.toString())
        }
    }

    private fun <N : Node> add(node: N): N {
        require(nodes.none { it.path == node.path }) { "config key ${node.path} declared twice" }
        val parent = node.path.substringBeforeLast('.', "")
        require(parent.isEmpty() || nodes.any { it is Section && it.path == parent }) { "declare section $parent before ${node.path}" }
        nodes += node
        return node
    }

    protected fun section(path: String, vararg comment: String) = add(Section(path, comment.toList()))

    protected fun string(path: String, default: String, vararg comment: String, reloadable: Boolean = true) =
        add(Key(path, comment.toList(), ConfigType.STRING, default, reloadable))

    protected fun integer(path: String, default: Int, vararg comment: String, min: Int? = null, max: Int? = null, reloadable: Boolean = true) =
        add(Key(path, comment.toList(), ConfigType.INTEGER, default, reloadable, min = min?.toDouble(), max = max?.toDouble()))

    protected fun boolean(path: String, default: Boolean, vararg comment: String, reloadable: Boolean = true) =
        add(Key(path, comment.toList(), ConfigType.BOOLEAN, default, reloadable))

    protected fun list(path: String, default: List<String>, vararg comment: String, reloadable: Boolean = true) =
        add(Key(path, comment.toList(), ConfigType.LIST, default, reloadable))

    protected fun duration(path: String, default: String, vararg comment: String, reloadable: Boolean = true) =
        add(Key(path, comment.toList(), ConfigType.DURATION, default, reloadable))

    protected fun choice(path: String, default: String, choices: List<String>, vararg comment: String, reloadable: Boolean = true): Key<String> {
        require(default in choices) { "$path: default $default isn't one of $choices" }
        return add(Key(path, comment.toList(), ConfigType.CHOICE, default, reloadable, choices))
    }

    /** The default config.yml, as the plugin writes it on first start. */
    fun defaultYaml(): String = buildString {
        for ((i, n) in nodes.withIndex()) {
            val indent = "  ".repeat(n.depth)
            if (i > 0 && n.depth == 0) append('\n')
            for (line in n.comment) append(indent).append("# ").append(line).append('\n')
            when (n) {
                is Section -> append(indent).append(n.key).append(":\n")
                is Key<*> -> when (val d = n.default) {
                    is List<*> ->
                        if (d.isEmpty()) {
                            append(indent).append(n.key).append(": []\n")
                        } else {
                            append(indent).append(n.key).append(":\n")
                            d.forEach { append(indent).append("  - ").append(quote(it as String)).append('\n') }
                        }
                    is String -> append(indent).append(n.key).append(": ").append(quote(d)).append('\n')
                    else -> append(indent).append(n.key).append(": ").append(d).append('\n')
                }
            }
        }
    }

    internal fun docs(): List<ConfigNodeDoc> {
        fun build(parent: String): List<ConfigNodeDoc> = nodes
            .filter { it.path.substringBeforeLast('.', "") == parent && it.path != parent }
            .map { n ->
                when (n) {
                    is Section -> ConfigNodeDoc(n.path, ConfigType.SECTION, n.comment, children = build(n.path))
                    is Key<*> -> ConfigNodeDoc(n.path, n.type, n.comment, n.json(), n.choices, n.min, n.max, n.reloadable)
                }
            }
        return build("")
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
