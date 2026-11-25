package co.xenastudios.core.common.config

import co.xenastudios.core.common.Outcome

/**
 * A sound as a config writes it: its Minecraft name, then optionally a volume (0 to 10) and
 * a pitch (0.5 to 2), `"block.anvil.use 0.5 1.2"`. Blank is none. A plugin declares one with
 * `key("sound", Sound.TYPE, Sound.parse("..."))` and its adapter plays it by name (xUtilities'
 * menus, zChat's mention and private-message pings).
 */
public class Sound(public val name: String, public val volume: Float = 1f, public val pitch: Float = 1f) {
    /** Whether it plays nothing. */
    public val isNone: Boolean get() = name.isEmpty()

    override fun equals(other: Any?): Boolean = other is Sound && other.name == name && other.volume == volume && other.pitch == pitch
    override fun hashCode(): Int = (name.hashCode() * 31 + volume.hashCode()) * 31 + pitch.hashCode()

    /** As the config writes it. */
    override fun toString(): String = when {
        isNone -> ""
        pitch != 1f -> "$name ${number(volume)} ${number(pitch)}"
        volume != 1f -> "$name ${number(volume)}"
        else -> name
    }

    public companion object {
        /** No sound. */
        public val NONE: Sound = Sound("")

        private val NAME = Regex("[a-z0-9_.:/-]+")

        /** A sound as the config writes it, or [NONE] if blank. Throws on one that can't be read: for defaults. */
        public fun parse(text: String): Sound = when (val read = read(text)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> throw IllegalArgumentException("\"$text\" ${read.error}")
        }

        internal fun read(text: String): Outcome<Sound, String> {
            val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return Outcome.Ok(NONE)
            if (parts.size > 3) return Outcome.Err("should be a sound's name, then optionally a volume and a pitch")
            val name = parts[0].lowercase()
            if (!NAME.matches(name)) return Outcome.Err("should start with a sound's name, such as block.anvil.use")
            val volume = parts.getOrNull(1)?.let { it.toFloatOrNull()?.takeIf { v -> v in 0f..10f } ?: return Outcome.Err("has a volume that isn't from 0 to 10") } ?: 1f
            val pitch = parts.getOrNull(2)?.let { it.toFloatOrNull()?.takeIf { p -> p in 0.5f..2f } ?: return Outcome.Err("has a pitch that isn't from 0.5 to 2") } ?: 1f
            return Outcome.Ok(Sound(name, volume, pitch))
        }

        /** How a sound reads and writes in the config. */
        public val TYPE: ConfigType<Sound> = object : ConfigType<Sound>() {
            override val name: String get() = "sound"
            override fun empty(): Sound = NONE
            override fun decode(node: YamlNode, context: ConfigContext): Outcome<Sound, String> =
                if (node is YamlNode.Scalar) read(node.content) else Outcome.Err("should be a single value")
            override fun encode(value: Sound): YamlNode = YamlNode.Scalar(value.toString())
        }

        private fun number(value: Float): String =
            if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()
    }
}
