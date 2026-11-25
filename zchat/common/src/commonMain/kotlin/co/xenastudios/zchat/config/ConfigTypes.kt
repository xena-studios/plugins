package co.xenastudios.zchat.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.permission.PermissionSet

/** The types zChat's settings are read with, apart from the schema so they exist before it's built. */
public object ConfigTypes {
    internal const val PING: String = "entity.experience_orb.pickup 0.5 1.2"

    /** A permission node, or blank for anyone (or no one, where the comment says). Read in any case, as Bukkit does. */
    public val PERMISSION: ConfigType<String> = ConfigType.scalar("permission", { it }) { raw ->
        val node = raw.trim().lowercase()
        if (node.isEmpty() || PermissionSet.isNode(node)) Outcome.Ok(node)
        else Outcome.Err("isn't a permission node (words of letters, digits, - and _ joined with .), or blank")
    }

    /** A command's other name: one word. */
    public val ALIAS: ConfigType<String> = ConfigType.scalar("alias", { it }) { raw ->
        val word = raw.trim().lowercase().removePrefix("/")
        if (WORD.matches(word)) Outcome.Ok(word) else Outcome.Err("isn't a command name (letters, digits, - and _)")
    }

    internal val ONE_CHARACTER: ConfigType<String> = ConfigType.scalar("character", { it }) { raw ->
        val count = raw.codePointCount()
        if (count == 1) Outcome.Ok(raw) else Outcome.Err("must be one character")
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
