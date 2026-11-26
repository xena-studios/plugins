package co.xenastudios.zchat.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.permission.PermissionSet

/** The types zChat's settings are read with, apart from the schema so they exist before it's built. */
public object ConfigTypes {
    internal const val PING: String = "entity.experience_orb.pickup 0.5 1.2"

    /** A permission node, or blank for anyone (or no one, where the comment says). Read in any case, as Bukkit does. */
    public val PERMISSION: ConfigType<String> = Text(0, "permission")

    /** A command's other name: one word. */
    public val ALIAS: ConfigType<String> = Text(1, "alias")

    internal val ONE_CHARACTER: ConfigType<String> = Text(2, "character")

    // The three as one class, told apart by [kind], rather than two lambdas each: the JVM makes a
    // class of every lambda as the config is read (decisions/2026-11-25-a-lighter-core.md).
    private class Text(private val kind: Int, override val name: String) : ConfigType<String>() {
        override fun decode(node: YamlNode, context: ConfigContext): Outcome<String, String> {
            if (node !is YamlNode.Scalar) return Outcome.Err("should be a single value")
            val raw = node.content
            return when (kind) {
                0 -> {
                    val permission = raw.trim().lowercase()
                    if (permission.isEmpty() || PermissionSet.isNode(permission)) Outcome.Ok(permission)
                    else Outcome.Err("isn't a permission node (words of letters, digits, - and _ joined with .), or blank")
                }
                1 -> {
                    val word = raw.trim().lowercase().removePrefix("/")
                    if (WORD.matches(word)) Outcome.Ok(word) else Outcome.Err("isn't a command name (letters, digits, - and _)")
                }
                else -> if (raw.codePointCount() == 1) Outcome.Ok(raw) else Outcome.Err("must be one character")
            }
        }

        override fun encode(value: String): YamlNode = YamlNode.Scalar(value)
    }

    internal val WORD = Regex("[a-z0-9][a-z0-9_-]*")

    private fun String.codePointCount(): Int {
        var n = 0
        var i = 0
        while (i < length) {
            i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}
