package co.xenastudios.xmarket.bukkit

import co.xenastudios.core.bukkit.BukkitServer
import co.xenastudios.xmarket.BukkitNames
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.bukkit.modern.Modern
import co.xenastudios.xmarket.price.ItemAliases
import co.xenastudios.xmarket.price.ItemSpec
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import java.nio.charset.StandardCharsets

/**
 * Bukkit's items as xMarket names them, both ways, on every server from 1.8.8: an item's id is
 * its material's key from 1.13, and before it, its material and data value looked up in
 * `xmarket/legacy.txt` (which Mojang's own data fixer made: `WOOL:14` is `minecraft:red_wool`).
 * A potion is its item and type (`long_swiftness`), from its base type (1.20.5), its potion data
 * (1.9) or its data value (1.8); an enchanted book its stored enchantments. An item with lore, a
 * custom model or another plugin's data on it isn't plain, and carries its full data
 * ([co.xenastudios.core.common.menu.Menus.data]) when the price list wants it.
 */
internal class BukkitItems(version: BukkitServer, private val menus: co.xenastudios.core.bukkit.BukkitMenus, private val server: BukkitMarketServer) {
    private val modern = version.atLeast(1, 13)
    private val potionTypes = version.atLeast(1, 20, 5)
    private val potionData = version.atLeast(1, 9)
    private val modelData = version.atLeast(1, 14)
    private val pdc = version.atLeast(1, 16, 5)

    @Volatile
    var renamedIsPlain: Boolean = true


    // Before 1.13: "WOOL:14" -> "red_wool", and back. Read on a server before 1.13 only.
    private val legacy: Map<String, String> = if (modern) emptyMap() else readLegacy()
    private val legacyBack: Map<String, String> = legacy.entries.associate { (k, v) -> v to k }

    private fun readLegacy(): Map<String, String> {
        val text = BukkitItems::class.java.classLoader.getResourceAsStream("xmarket/legacy.txt")?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: return emptyMap()
        val out = HashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (from, to) = line.trim().split(' ', limit = 2)
            out[if (':' in from) from else "$from:0"] = to
        }
        return out
    }

    /** A Bukkit material, as configs write it (`DIAMOND`, `WOOL:14` before 1.13), as Minecraft's id; `null` if unknown. */
    fun material(name: String): String? {
        val n = name.trim().uppercase()
        if (!modern) {
            val key = if (':' in n) n else "$n:0"
            return legacy[key]?.let { "minecraft:$it" } ?: legacy["${n.substringBefore(':')}:0"]?.let { "minecraft:$it" }
        }
        val m = Material.matchMaterial(n.substringBefore(':')) ?: return null
        return ItemAliases.current("minecraft:" + m.name.lowercase())
    }

    @Suppress("DEPRECATION")
    fun key(stack: ItemStack): ItemKey {
        val meta = if (stack.hasItemMeta()) stack.itemMeta else null
        var id = id(stack)
        var potion: String? = null
        var enchantments: Map<String, Int> = emptyMap()
        if (meta is PotionMeta) potion = potionOf(meta)
        if (!modern && stack.type.name == "POTION") {
            val p = LegacyPotions.read(stack)
            if (p == null) {
                potion = "water"
            } else {
                if (p.second) id = "minecraft:splash_potion"
                potion = modernPotion(p.first.first, p.first.second, p.first.third)
            }
        }
        if (meta is EnchantmentStorageMeta && meta.hasStoredEnchants()) {
            enchantments = meta.storedEnchants.entries.associate { (e, level) -> enchantmentId(e) to level }
        }
        var plain = true
        if (meta != null) {
            if (meta.hasLore()) plain = false
            if (meta.hasDisplayName() && !renamedIsPlain) plain = false
            if ((modelData || pdc) && Modern.marked(meta, modelData, pdc)) plain = false
        }
        val full = if (!plain && server.wants(id)) menus.data(stack) else null
        return ItemKey(id, stack.amount, potion, enchantments, plain, full)
    }

    @Suppress("DEPRECATION")
    fun id(stack: ItemStack): String {
        if (modern) return ItemAliases.current("minecraft:" + stack.type.name.lowercase())
        val name = stack.type.name
        return legacy["$name:${stack.durability}"]?.let { "minecraft:$it" } ?: legacy["$name:0"]?.let { "minecraft:$it" } ?: "legacy:${name.lowercase()}"
    }

    private fun potionOf(meta: PotionMeta): String? = when {
        potionTypes -> Modern.potionType(meta)
        potionData -> Modern.potionData(meta)?.let { (name, long, strong) -> modernPotion(name, long, strong) }
        else -> null
    }

    private fun modernPotion(bukkit: String, long: Boolean, strong: Boolean): String {
        val base = BukkitNames.POTIONS[bukkit] ?: bukkit.lowercase()
        return when {
            strong -> "strong_$base"
            long -> "long_$base"
            else -> base
        }
    }

    @Suppress("DEPRECATION")
    private fun enchantmentId(e: Enchantment): String =
        if (modern) Modern.enchantmentKey(e) else "minecraft:" + (BukkitNames.LEGACY_ENCHANTMENTS[e.name] ?: e.name.lowercase())

    /** One of [spec], as this server makes it; `null` if it has no such item. */
    @Suppress("DEPRECATION")
    fun create(spec: ItemSpec): ItemStack? {
        spec.data?.let { text -> read(text)?.let { return it } }
        val stack = base(spec) ?: return null
        val meta = stack.itemMeta ?: return stack
        val potion = spec.potion
        if (potion != null && meta is PotionMeta && potionData) {
            val ok = if (potionTypes) Modern.setPotionType(meta, potion) else {
                val (name, long, strong) = bukkitPotion(potion)
                Modern.setPotionData(meta, name, long, strong)
            }
            if (!ok) return null
        }
        if (spec.enchantments.isNotEmpty() && meta is EnchantmentStorageMeta) {
            for ((key, level) in spec.enchantments) {
                val e = if (modern) Modern.enchantment(key) else Enchantment.getByName(Back.ENCHANTMENTS[key.removePrefix("minecraft:")] ?: key.removePrefix("minecraft:").uppercase())
                if (e == null) return null
                meta.addStoredEnchant(e, level, true)
            }
        }
        stack.itemMeta = meta
        return stack
    }

    @Suppress("DEPRECATION")
    private fun base(spec: ItemSpec): ItemStack? {
        val short = spec.shortId
        if (modern) {
            val material = Material.matchMaterial(spec.id) ?: ItemAliases.older(spec.id)?.let { Material.matchMaterial(it) }
            return material?.takeIf { it != Material.AIR }?.let { ItemStack(it) }
        }
        if (short == "potion" || short == "splash_potion") {
            val (name, long, strong) = bukkitPotion(spec.potion ?: "water")
            if (name == "WATER") return ItemStack(Material.POTION)
            return LegacyPotions.make(name, long, strong, short == "splash_potion")
        }
        val key = legacyBack[short] ?: return null
        val material = Material.getMaterial(key.substringBefore(':')) ?: return null
        return ItemStack(material, 1, key.substringAfter(':').toShort())
    }

    // long_swiftness -> SPEED, long; strong_healing -> INSTANT_HEAL, strong.
    private fun bukkitPotion(modern: String): Triple<String, Boolean, Boolean> {
        val long = modern.startsWith("long_")
        val strong = modern.startsWith("strong_")
        val base = modern.removePrefix("long_").removePrefix("strong_")
        val name = Back.POTIONS[base] ?: base.uppercase()
        return Triple(name, long, strong)
    }

    /** An item from its full data, as [data] wrote it. */
    fun read(text: String): ItemStack? = try {
        val yaml = YamlConfiguration()
        yaml.loadFromString(text)
        yaml.getItemStack("item")?.takeIf { it.type != Material.AIR }?.also { it.amount = 1 }
    } catch (e: Exception) {
        null
    }

    /** Every item id the server has, for `/shop audit`. */
    fun known(): Set<String> {
        if (!modern) return legacy.entries.filter { Material.getMaterial(it.key.substringBefore(':')) != null }.map { "minecraft:" + it.value }.toSet()
        return Modern.items().map(ItemAliases::current).toSet()
    }

    // Made the first time a potion or an old enchantment is: its own object.
    private object Back {
        val POTIONS: Map<String, String> = BukkitNames.POTIONS.entries.filter { it.key != "UNCRAFTABLE" }.associate { (k, v) -> v to k }
        val ENCHANTMENTS: Map<String, String> = BukkitNames.LEGACY_ENCHANTMENTS.entries.associate { (k, v) -> v to k }
    }
}

/** Potions on 1.8, by data value: its own object, so no newer server loads Bukkit's old `Potion` class. */
@Suppress("DEPRECATION")
internal object LegacyPotions {
    /** The type's Bukkit name, long and strong; and whether it's a splash potion. `null` for a water bottle. */
    fun read(stack: ItemStack): Pair<Triple<String, Boolean, Boolean>, Boolean>? = try {
        val p = org.bukkit.potion.Potion.fromItemStack(stack)
        val t = p.type
        if (t == null) null else Triple(t.name, p.hasExtendedDuration(), p.level >= 2) to p.isSplash
    } catch (e: Exception) {
        null
    }

    fun make(name: String, long: Boolean, strong: Boolean, splash: Boolean): ItemStack? = try {
        val type = org.bukkit.potion.PotionType.values().firstOrNull { it.name == name }
        type?.let { org.bukkit.potion.Potion(it, if (strong) 2 else 1, splash, long && !strong).toItemStack(1) }
    } catch (e: Exception) {
        null
    }
}
