package co.xenastudios.xmarket.price

/**
 * Items Minecraft renamed since 1.13, which the price list knows by their newest names: an older
 * server's `minecraft:grass` is the list's `short_grass`. The adapters read an item's id through
 * [current] and make one from the list through [older] where the newest name isn't the server's.
 */
public object ItemAliases {
    private val RENAMED: Map<String, String> = mapOf(
        "minecraft:grass" to "minecraft:short_grass",
        "minecraft:scute" to "minecraft:turtle_scute",
        "minecraft:chain" to "minecraft:iron_chain",
        "minecraft:sign" to "minecraft:oak_sign",
        "minecraft:cactus_green" to "minecraft:green_dye",
        "minecraft:dandelion_yellow" to "minecraft:yellow_dye",
        "minecraft:rose_red" to "minecraft:red_dye",
        "minecraft:grass_path" to "minecraft:dirt_path",
    )

    private val OLDER: Map<String, String> = RENAMED.entries.associate { (old, new) -> new to old }

    /** [id] by its newest name. */
    public fun current(id: String): String = RENAMED[id] ?: id

    /** The name [id] had before its rename, or `null` if it wasn't renamed. */
    public fun older(id: String): String? = OLDER[id]
}
