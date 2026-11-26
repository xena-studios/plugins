package co.xenastudios.zchat.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode

/** The types zChat's settings are read with, apart from the schema so they exist before it's built. */
public object ConfigTypes {
    internal const val PING: String = "entity.experience_orb.pickup 0.5 1.2"

    /** A permission node, or blank: the core's ([co.xenastudios.core.common.config.CommandTypes]). */
    public val PERMISSION: ConfigType<String> = co.xenastudios.core.common.config.CommandTypes.PERMISSION

    /** A command's other name: the core's. */
    public val ALIAS: ConfigType<String> = co.xenastudios.core.common.config.CommandTypes.ALIAS

    internal val ONE_CHARACTER: ConfigType<String> = Text("character")

    private class Text(override val name: String) : ConfigType<String>() {
        override fun decode(node: YamlNode, context: ConfigContext): Outcome<String, String> {
            if (node !is YamlNode.Scalar) return Outcome.Err("should be a single value")
            val raw = node.content
            return if (raw.codePointCount() == 1) Outcome.Ok(raw) else Outcome.Err("must be one character")
        }

        override fun encode(value: String): YamlNode = YamlNode.Scalar(value)
    }

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
