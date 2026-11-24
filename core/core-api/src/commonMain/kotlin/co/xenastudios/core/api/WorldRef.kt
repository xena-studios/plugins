package co.xenastudios.core.api

import kotlin.jvm.JvmStatic

/**
 * A world, by the name the platform keeps for it from one start to the next: its folder name
 * on Bukkit (`world`, `world_nether`), its dimension's id on the mod loaders and Pumpkin
 * (`minecraft:overworld`). It may name a world that isn't loaded; asking the platform for it
 * then finds nothing.
 *
 * Two references are equal when their names are.
 */
public class WorldRef private constructor(
    /** The world's name on its platform. Never blank. */
    public val name: String,
) {
    override fun equals(other: Any?): Boolean = other is WorldRef && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    public companion object {
        /** The world called [name]. Throws if it's blank, which no world is. */
        @JvmStatic
        public fun of(name: String): WorldRef {
            require(name.isNotBlank()) { "A world's name can't be blank" }
            return WorldRef(name)
        }
    }
}
