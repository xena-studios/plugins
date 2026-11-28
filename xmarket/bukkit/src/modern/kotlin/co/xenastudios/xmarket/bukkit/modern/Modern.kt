package co.xenastudios.xmarket.bukkit.modern

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionData
import org.bukkit.potion.PotionType

/**
 * What only newer servers have, called only once a check at startup found it: potion types as
 * keys (1.20.5), potion data (1.9 to 1.20.4), enchantments and materials by key (1.13),
 * custom model data (1.14) and plugins' data on items (1.16.5).
 */
internal object Modern {
    fun key(material: Material): String = material.key.toString()

    /** Air by name: `Material.isAir` is 1.14 on, and this runs from 1.13. */
    fun items(): Set<String> = Material.entries.filter { !it.name.startsWith("LEGACY_") && it.isItem && it.name !in AIR }.map { it.key.toString() }.toSet()

    private val AIR = setOf("AIR", "CAVE_AIR", "VOID_AIR")

    fun enchantmentKey(enchantment: Enchantment): String = enchantment.key.toString()

    @Suppress("DEPRECATION")
    fun enchantment(key: String): Enchantment? = NamespacedKey.fromString(key)?.let { Enchantment.getByKey(it) }

    /** A potion's type as Minecraft names it, from 1.20.5. */
    fun potionType(meta: PotionMeta): String? = if (meta.hasBasePotionType()) meta.basePotionType?.key?.key else null

    fun setPotionType(meta: PotionMeta, name: String): Boolean {
        val type = PotionType.entries.firstOrNull { it.key.key == name } ?: return false
        meta.basePotionType = type
        return true
    }

    /** 1.9 to 1.20.4: the type's Bukkit name, and whether it's long or strong. */
    @Suppress("DEPRECATION", "REMOVAL")
    fun potionData(meta: PotionMeta): Triple<String, Boolean, Boolean>? {
        val data = meta.basePotionData ?: return null
        return Triple(data.type.name, data.isExtended, data.isUpgraded)
    }

    @Suppress("DEPRECATION", "REMOVAL")
    fun setPotionData(meta: PotionMeta, bukkitName: String, long: Boolean, strong: Boolean): Boolean {
        val type = PotionType.entries.firstOrNull { it.name == bukkitName } ?: return false
        meta.basePotionData = PotionData(type, long, strong)
        return true
    }

    @Suppress("DEPRECATION")
    /** Whether a plugin or a resource pack marked it: a custom model, or plugins' own data on it. */
    fun marked(meta: ItemMeta, modelData: Boolean, pdc: Boolean): Boolean =
        (modelData && meta.hasCustomModelData()) || (pdc && !meta.persistentDataContainer.isEmpty)
}
