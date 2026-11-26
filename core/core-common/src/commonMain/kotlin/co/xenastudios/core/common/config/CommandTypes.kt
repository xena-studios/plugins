package co.xenastudios.core.common.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.permission.PermissionSet

/**
 * The types a command's settings are read with, the same in every plugin (`commands.<name>`'s
 * `aliases` and `permission`): from xUtilities and zChat, which each had their own, now that
 * xMoney needs them too.
 */
public object CommandTypes {
    /** A permission node, or blank for anyone (or no one, where the comment says). Read in any case, as Bukkit does. */
    public val PERMISSION: ConfigType<String> = Text(0, "permission")

    /** A command's other name: one word, without the slash. */
    public val ALIAS: ConfigType<String> = Text(1, "alias")

    /** Whether [word] can be a command's name or alias. */
    public fun isCommandName(word: String): Boolean = WORD.matches(word)

    private val WORD = Regex("[a-z0-9][a-z0-9_-]*")

    // The two as one class, told apart by [kind], rather than two lambdas each: the JVM makes a
    // class of every lambda as the config is read (decisions/2026-11-25-a-lighter-core.md).
    private class Text(private val kind: Int, override val name: String) : ConfigType<String>() {
        override fun decode(node: YamlNode, context: ConfigContext): Outcome<String, String> {
            if (node !is YamlNode.Scalar) return Outcome.Err("should be a single value")
            val raw = node.content
            return if (kind == 0) {
                val permission = raw.trim().lowercase()
                if (permission.isEmpty() || PermissionSet.isNode(permission)) Outcome.Ok(permission)
                else Outcome.Err("isn't a permission node (words of letters, digits, - and _ joined with .), or blank")
            } else {
                val word = raw.trim().lowercase().removePrefix("/")
                if (WORD.matches(word)) Outcome.Ok(word) else Outcome.Err("isn't a command name (letters, digits, - and _)")
            }
        }

        override fun encode(value: String): YamlNode = YamlNode.Scalar(value)
    }
}
