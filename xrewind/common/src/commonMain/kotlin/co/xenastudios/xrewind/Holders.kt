package co.xenastudios.xrewind

/**
 * Which blocks hold contents worth keeping with their break (a container's items, a sign's text),
 * by id, the same on every platform and every version from 1.13: worked out from the name, so a
 * listener can skip the rest without looking at the block's state.
 */
public object Holders {
    private val CONTAINERS = setOf(
        "chest", "trapped_chest", "barrel", "furnace", "blast_furnace", "smoker", "hopper", "dropper", "dispenser",
        "brewing_stand", "chiseled_bookshelf", "crafter", "decorated_pot",
    )

    /** Whether blocks of [type] (`minecraft:chest`) hold items or text. */
    public fun holds(type: String): Boolean {
        val name = type.substringAfter(':')
        return name in CONTAINERS || name.endsWith("shulker_box") || name.endsWith("_sign")
    }

    /** Whether it's a sign of any kind, wall and hanging ones included. */
    public fun isSign(type: String): Boolean = type.endsWith("_sign")
}
