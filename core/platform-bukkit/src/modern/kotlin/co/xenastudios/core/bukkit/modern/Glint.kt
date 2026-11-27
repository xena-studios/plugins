package co.xenastudios.core.bukkit.modern

import org.bukkit.inventory.meta.ItemMeta

/** A menu icon's shine, from 1.20.5: the glint itself, with no enchantment to hide (BukkitMenus). */
internal object Glint {
    fun on(meta: ItemMeta) {
        meta.setEnchantmentGlintOverride(true)
    }
}
