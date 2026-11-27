package co.xenastudios.xmarket.price

import co.xenastudios.xmarket.api.ItemKey

/**
 * What an entry gives and takes: an item by its namespaced id, and what about it the price
 * depends on. A potion's type or an enchanted book's enchantments for the price list's own
 * entries; for an item an owner added in game, the platform's full text of it ([data]), which a
 * sold item must match exactly (all but the amount).
 */
public class ItemSpec(
    /** `minecraft:diamond`. */
    public val id: String,
    public val potion: String? = null,
    /** By namespaced id, `minecraft:sharpness`: a book's stored enchantments, or an item's own. */
    public val enchantments: Map<String, Int> = emptyMap(),
    /** The platform's full item text, or `null` for an item that's its id (and potion, enchantments). */
    public val data: String? = null,
) {
    /** The id without `minecraft:`, as files and the recipes write it. */
    public val shortId: String get() = id.removePrefix("minecraft:")

    /**
     * The key the price list's recipes and the loop audit know it by: the short id, with its
     * potion or enchantments in braces, `potion{swiftness}`, `enchanted_book{sharpness=5}`.
     */
    public val key: String
        get() = when {
            potion != null -> "$shortId{$potion}"
            enchantments.isNotEmpty() -> shortId + enchantments.entries.sortedBy { it.key }
                .joinToString(",", "{", "}") { "${it.key.removePrefix("minecraft:")}=${it.value}" }
            else -> shortId
        }

    /** Whether it's more than an id: [data], a potion or enchantments, matched before plain entries. */
    public val specific: Int
        get() = when {
            data != null -> 2
            potion != null || enchantments.isNotEmpty() -> 1
            else -> 0
        }

    /**
     * Whether [item] is this: the same id; then the same full data for an entry with data; the
     * same potion and enchantments for one with those; and for a plain entry, an item that's plain
     * (or, with [anyName], one a player only renamed) with no potion or enchantments the price
     * list prices apart.
     */
    public fun matches(item: ItemKey): Boolean {
        if (item.id != id) return false
        if (data != null) return item.data == data
        if (potion != null) return item.potion == potion
        if (enchantments.isNotEmpty()) return item.enchantments == enchantments
        return item.plain
    }

    /** An item of this, [amount] of it, as the API names one. */
    public fun toKey(amount: Int = 1): ItemKey = ItemKey(id, amount, potion, enchantments, data == null, data)

    override fun equals(other: Any?): Boolean =
        other is ItemSpec && other.id == id && other.potion == potion && other.enchantments == enchantments && other.data == data

    override fun hashCode(): Int = ((id.hashCode() * 31 + (potion?.hashCode() ?: 0)) * 31 + enchantments.hashCode()) * 31 + (data?.hashCode() ?: 0)

    override fun toString(): String = key

    public companion object {
        /** The spec a recipe or price key names: `oak_planks`, `potion{swiftness}`, `enchanted_book{sharpness=5}`. */
        public fun ofKey(key: String): ItemSpec {
            val brace = key.indexOf('{')
            if (brace < 0) return ItemSpec(ItemKey.namespaced(key))
            val id = ItemKey.namespaced(key.substring(0, brace))
            val inside = key.substring(brace + 1, key.length - 1)
            if ('=' !in inside) return ItemSpec(id, potion = inside)
            val enchantments = LinkedHashMap<String, Int>()
            for (part in inside.split(',')) {
                val (name, level) = part.split('=', limit = 2)
                enchantments[ItemKey.namespaced(name)] = level.toIntOrNull() ?: 1
            }
            return ItemSpec(id, enchantments = enchantments)
        }
    }
}
