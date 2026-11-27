package co.xenastudios.xmarket

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.ImportPage
import co.xenastudios.core.common.admin.Importer
import co.xenastudios.core.common.admin.Imports
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.Currency
import co.xenastudios.xmarket.price.Entry
import co.xenastudios.xmarket.price.ItemSpec
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files as NioFiles

/**
 * `/xmarket import`: other shops' configs as xMarket's categories, each into its own
 * `shops/<plugin>-<section>.yml` so nothing of the price list is overwritten (delete the
 * defaults you don't want). A dry run (`--dry-run`) counts what would come over and logs each
 * item that won't, and why. Importing again replaces what the last import wrote.
 *
 * - **EconomyShopGUI**: `sections/` (names, slots) and `shops/` (pages of items), or
 *   the older single `shops.yml`. Items, prices, names, lore and enchantments come over;
 *   EconomyShopGUI's Premium-only settings (limits, requirements, item NBT) are logged as left out.
 * - **ShopGUI+**: `shops/` or `shops.yml`: item and command entries, prices, names, lore and
 *   enchantments.
 * - **EssentialsX's `worth.yml`**: sell prices, one category of what it prices.
 *
 * [materials] turns a Bukkit material (with a data value before 1.13) into Minecraft's id.
 */
public class FileImports(
    private val plugin: XMarketPlugin,
    private val plugins: Path,
    private val files: Files,
    private val names: MaterialNames,
) {
    private fun materials(name: String): String? = names.id(name)

    /** One item of another shop's, as it'll be xMarket's, or why it won't. */
    public class Record(public val category: String, public val categoryName: String, public val slot: Int, public val entry: Entry?, public val label: String, public val skip: String?)

    /** The records of source [kind] (0 EconomyShopGUI, 1 ShopGUI+, 2 EssentialsX), from its plugin's folder. */
    internal fun read(kind: Int, folder: Path): List<Record> = when (kind) {
        0 -> economyShopGui(folder)
        1 -> shopGuiPlus(folder)
        else -> essentials(folder)
    }

    // ---- EconomyShopGUI ----

    private fun economyShopGui(root: Path): List<Record> {
        val out = ArrayList<Record>()
        val sections = HashMap<String, YamlNode.Mapping>()
        root.resolve("sections").takeIf(NioFiles::isDirectory)?.let { dir ->
            for (f in list(dir)) (yaml(f) as? YamlNode.Mapping)?.let { sections[name(f)] = it }
        }
        val shopFiles = root.resolve("shops").takeIf(NioFiles::isDirectory)?.let(::list).orEmpty()
        if (shopFiles.isNotEmpty()) {
            for (f in shopFiles) {
                val section = name(f)
                val map = yaml(f) as? YamlNode.Mapping ?: continue
                val info = sections[section]
                val title = info?.let { text(it["title"]) ?: text(it["displayname"]) } ?: section
                val slot = info?.let { int(it["slot"]) } ?: -1
                val pages = map["pages"] as? YamlNode.Mapping
                val items = if (pages != null) pages.entries.flatMap { ((it.value as? YamlNode.Mapping)?.get("items") as? YamlNode.Mapping)?.entries.orEmpty() } else map.entries
                for (item in items) esguiItem(section, title, slot, item, out)
            }
        } else {
            // The single shops.yml of older versions: a section of items each.
            val map = yaml(root.resolve("shops.yml")) as? YamlNode.Mapping ?: return out
            for (section in map.entries) {
                val m = section.value as? YamlNode.Mapping ?: continue
                for (item in m.entries) esguiItem(slug(section.key), section.key, -1, item, out)
            }
        }
        return out
    }

    private fun esguiItem(section: String, title: String, slot: Int, item: YamlNode.Mapping.Entry, out: MutableList<Record>) {
        val m = item.value as? YamlNode.Mapping ?: return
        val material = text(m["material"]) ?: text(m["item"]) ?: return
        val label = "$material (#${item.key})"
        val leftOut = listOf("limit", "item-limit", "requirements", "nbt", "nbt-data", "skull-texture").filter { m[it] != null }
        val id = materials(material) ?: return run { out += Record(slug(section), colours(title), slot, null, label, "$material isn't an item this server knows") }
        val buy = price(m["buy"])
        val sell = price(m["sell"])
        val spec = spec(id, m["enchantments"], m["potiontypes"] ?: m["potion-type"])
        val commands = list(m["commands"]) + listOfNotNull(text(m["command"]))
        val entry = Entry(
            entryId(spec, item.key), spec, buy, if (commands.isEmpty()) sell else null, amount = int(m["stack-size"]) ?: 1,
            name = text(m["name"])?.let(::colours), lore = list(m["lore"]).map(::colours), commands = commands,
            icon = if (commands.isEmpty()) null else spec,
        )
        if (leftOut.isNotEmpty()) plugin.log.info("Import from EconomyShopGUI: $section's $label comes over without ${leftOut.joinToString(", ")}, which xMarket sets its own way.")
        out += Record(slug(section), colours(title), slot, entry, label, null)
    }

    // ---- ShopGUI+ ----

    private fun shopGuiPlus(root: Path): List<Record> {
        val out = ArrayList<Record>()
        val sources = root.resolve("shops").takeIf(NioFiles::isDirectory)?.let(::list) ?: listOf(root.resolve("shops.yml"))
        for (f in sources) {
            val map = yaml(f) as? YamlNode.Mapping ?: continue
            for (shop in map.entries) {
                val m = shop.value as? YamlNode.Mapping ?: continue
                val title = text(m["name"]) ?: shop.key
                val items = m["items"] as? YamlNode.Mapping ?: continue
                for (item in items.entries) {
                    val i = item.value as? YamlNode.Mapping ?: continue
                    val type = text(i["type"])?.lowercase() ?: "item"
                    val stack = i["item"] as? YamlNode.Mapping
                    val material = stack?.let { text(it["material"]) } ?: continue
                    val data = stack.let { int(it["damage"]) }
                    val label = "$material (#${item.key})"
                    val id = materials(if (data != null && data != 0) "$material:$data" else material)
                    if (id == null) {
                        out += Record(slug(shop.key), colours(title), -1, null, label, "$material isn't an item this server knows")
                        continue
                    }
                    if (type !in setOf("item", "command")) {
                        out += Record(slug(shop.key), colours(title), -1, null, label, "a $type entry, which xMarket doesn't have")
                        continue
                    }
                    val spec = spec(id, stack["enchantments"], (stack["potion"] as? YamlNode.Mapping)?.get("type") ?: stack["potionType"])
                    val commands = if (type == "command") list(i["commands"]) else emptyList()
                    val entry = Entry(
                        entryId(spec, item.key), spec, price(i["buyPrice"]), if (commands.isEmpty()) price(i["sellPrice"]) else null,
                        amount = int(stack["quantity"]) ?: 1, name = text(stack["name"])?.let(::colours), lore = list(stack["lore"]).map(::colours),
                        commands = commands, icon = if (commands.isEmpty()) null else spec,
                    )
                    out += Record(slug(shop.key), colours(title), -1, entry, label, null)
                }
            }
        }
        return out
    }

    // ---- EssentialsX ----

    private fun essentials(root: Path): List<Record> {
        val worth = (yaml(root.resolve("worth.yml")) as? YamlNode.Mapping)?.get("worth") as? YamlNode.Mapping ?: return emptyList()
        val out = ArrayList<Record>()
        for (item in worth.entries) {
            val value = item.value
            val pairs = if (value is YamlNode.Mapping) value.entries.map { "${item.key}:${it.key}" to it.value } else listOf(item.key to value)
            for ((name, node) in pairs) {
                val price = price(node) ?: continue
                val id = materials(name) ?: materials(name.substringBefore(':'))
                if (id == null) {
                    out += Record("worth", "Essentials worth", -1, null, name, "$name isn't an item this server knows")
                    continue
                }
                val spec = ItemSpec(id)
                out += Record("worth", "Essentials worth", -1, Entry(spec.key, spec, null, price), name, null)
            }
        }
        return out
    }

    // ---- shared ----

    private fun spec(id: String, enchantments: YamlNode?, potion: YamlNode?): ItemSpec {
        val e = LinkedHashMap<String, Int>()
        for (line in list(enchantments)) {
            val (name, level) = line.split(':', limit = 2).let { it[0] to (it.getOrNull(1)?.trim()?.toIntOrNull() ?: 1) }
            e[ItemKey.namespaced(BukkitNames.LEGACY_ENCHANTMENTS[name.trim().uppercase()] ?: name.trim().lowercase())] = level
        }
        val p = text(potion)?.let { BukkitNames.POTIONS[it.trim().uppercase()] ?: it.trim().lowercase() }
        return ItemSpec(id, potion = p, enchantments = if (p == null) e else emptyMap())
    }

    private fun entryId(spec: ItemSpec, key: String): String = if (spec.data == null && spec.specific == 0) spec.key else "${spec.shortId}_$key"

    private fun price(node: YamlNode?): Money? = text(node)?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.setScale(4, java.math.RoundingMode.HALF_UP)?.let { Money.ofUnits(it.unscaledValue().toLong()) }

    private fun list(dir: Path): List<Path> = NioFiles.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".yml") }.sorted().collect(java.util.stream.Collectors.toList()) }

    private fun name(f: Path): String = f.fileName.toString().removeSuffix(".yml")

    private fun yaml(f: Path): YamlNode? = try {
        if (!NioFiles.isRegularFile(f)) null else (JvmYaml.read(String(NioFiles.readAllBytes(f), StandardCharsets.UTF_8)) as? Outcome.Ok)?.value
    } catch (e: Exception) {
        null
    }

    private fun text(node: YamlNode?): String? = (node as? YamlNode.Scalar)?.content

    private fun int(node: YamlNode?): Int? = text(node)?.trim()?.toIntOrNull()

    private fun list(node: YamlNode?): List<String> = when (node) {
        is YamlNode.Sequence -> node.items.mapNotNull { text(it) }
        is YamlNode.Scalar -> listOf(node.content)
        else -> emptyList()
    }

    private fun slug(text: String): String = colours(text).let { Regex("<[^>]+>").replace(it, "") }.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "shop" }

    /** `&a` and `§a` colour codes as our markup. */
    internal fun colours(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if ((c == '&' || c == '§') && i + 1 < text.length) {
                val tag = CODES[text[i + 1].lowercaseChar()]
                if (tag != null) {
                    out.append('<').append(tag).append('>')
                    i += 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private companion object {
        val CODES = mapOf(
            '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua", '4' to "dark_red", '5' to "dark_purple", '6' to "gold",
            '7' to "gray", '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua", 'c' to "red", 'd' to "light_purple", 'e' to "yellow",
            'f' to "white", 'l' to "bold", 'o' to "italic", 'n' to "underlined", 'm' to "strikethrough", 'k' to "obfuscated", 'r' to "reset",
        )
    }
}

/** A Bukkit material's name, as other shops' configs write it (`DIAMOND`, `WOOL:14`), as Minecraft's id; `null` if unknown. */
public fun interface MaterialNames {
    public fun id(name: String): String?
}

/** One of `/xmarket import`'s sources. */
internal class Source(
    private val kind: Int, id: String, display: String, private val folder: String,
    private val plugin: XMarketPlugin, private val plugins: Path, private val files: Files, private val names: MaterialNames,
) : Importer<FileImports.Record>(id, display) {
        @Volatile
        private var cached: List<FileImports.Record>? = null

        private fun records(): List<FileImports.Record> = cached ?: FileImports(plugin, plugins, files, names).read(kind, plugins.resolve(folder)).also { cached = it }

        override fun detect(): Pending<Boolean> = pendingOf(NioFiles.isDirectory(plugins.resolve(folder)).also { cached = null })

        override fun count(): Pending<Outcome<Long, String>> = pendingOf(
            try {
                Outcome.Ok(records().size.toLong())
            } catch (e: Exception) {
                Outcome.Err(e.toString())
            },
        )

        override fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<FileImports.Record>, String>> {
            val all = try {
                records()
            } catch (e: Exception) {
                return pendingOf(Outcome.Err(e.toString()))
            }
            val from = cursor?.toIntOrNull() ?: 0
            val to = minOf(all.size, from + limit)
            return pendingOf(Outcome.Ok(ImportPage(all.subList(from, to), if (to < all.size) to.toString() else null)))
        }

        override fun describe(record: FileImports.Record): String = "${record.categoryName}: ${record.label}"

        override fun skip(record: FileImports.Record): String? = record.skip?.also { plugin.log.info("Import from $displayName leaves out ${describe(record)}: $it") }

        override fun write(record: FileImports.Record): Pending<Outcome<Unit, String>> {
            val entry = record.entry ?: return pendingOf(Outcome.Err(record.skip ?: "nothing to import"))
            val id = "$id-${record.category}"
            val current = plugin.prices.categories.firstOrNull { it.id == id }
                ?: Category(id, record.categoryName, entry.item, record.slot, plugin.config[co.xenastudios.xmarket.config.XMarketConfig.shop.currency], null, emptyList(), emptyList())
            val entries = current.entries.filter { it.id != entry.id } + entry
            val updated = current.withEntries(entries)
            val error = files.save(updated)
            if (error != null) return pendingOf(Outcome.Err(error))
            plugin.replace(updated)
            return pendingOf(Outcome.Ok(Unit))
        }
}


/** Bukkit's own names for enchantments and potion types, before 1.20.5 (and before 1.13), as Minecraft names them. */
public object BukkitNames {
    /** Bukkit's enchantment names before 1.20.5 (and before 1.13), as Minecraft names them. */
    @JvmField
    public val LEGACY_ENCHANTMENTS: Map<String, String> = mapOf(
        "PROTECTION_ENVIRONMENTAL" to "protection", "PROTECTION_FIRE" to "fire_protection", "PROTECTION_FALL" to "feather_falling",
        "PROTECTION_EXPLOSIONS" to "blast_protection", "PROTECTION_PROJECTILE" to "projectile_protection", "OXYGEN" to "respiration",
        "WATER_WORKER" to "aqua_affinity", "THORNS" to "thorns", "DEPTH_STRIDER" to "depth_strider", "FROST_WALKER" to "frost_walker",
        "BINDING_CURSE" to "binding_curse", "DAMAGE_ALL" to "sharpness", "DAMAGE_UNDEAD" to "smite", "DAMAGE_ARTHROPODS" to "bane_of_arthropods",
        "KNOCKBACK" to "knockback", "FIRE_ASPECT" to "fire_aspect", "LOOT_BONUS_MOBS" to "looting", "SWEEPING_EDGE" to "sweeping_edge",
        "DIG_SPEED" to "efficiency", "SILK_TOUCH" to "silk_touch", "DURABILITY" to "unbreaking", "LOOT_BONUS_BLOCKS" to "fortune",
        "ARROW_DAMAGE" to "power", "ARROW_KNOCKBACK" to "punch", "ARROW_FIRE" to "flame", "ARROW_INFINITE" to "infinity",
        "LUCK" to "luck_of_the_sea", "LURE" to "lure", "MENDING" to "mending", "VANISHING_CURSE" to "vanishing_curse",
    )

    /** Bukkit's potion types before 1.20.5, as Minecraft names them. */
    @JvmField
    public val POTIONS: Map<String, String> = mapOf(
        "SPEED" to "swiftness", "SLOWNESS" to "slowness", "INSTANT_HEAL" to "healing", "INSTANT_DAMAGE" to "harming", "JUMP" to "leaping",
        "REGEN" to "regeneration", "STRENGTH" to "strength", "FIRE_RESISTANCE" to "fire_resistance", "WATER_BREATHING" to "water_breathing",
        "INVISIBILITY" to "invisibility", "NIGHT_VISION" to "night_vision", "WEAKNESS" to "weakness", "POISON" to "poison",
        "TURTLE_MASTER" to "turtle_master", "SLOW_FALLING" to "slow_falling", "WATER" to "water", "AWKWARD" to "awkward",
        "MUNDANE" to "mundane", "THICK" to "thick", "UNCRAFTABLE" to "water",
    )
}

/**
 * `/xmarket import`'s sources: one small class each, which makes the importer itself (and reads
 * nothing) only when an import first runs, so the server's startup loads none of it.
 */
public fun importSources(plugin: XMarketPlugin, plugins: Path, files: Files, names: MaterialNames): Imports = Imports(
    plugin.platform,
    listOf(
        Source(0, "economyshopgui", "EconomyShopGUI", "EconomyShopGUI", plugin, plugins, files, names),
        Source(1, "shopguiplus", "ShopGUI+", "ShopGUIPlus", plugin, plugins, files, names),
        Source(2, "essentials", "EssentialsX worth.yml", "Essentials", plugin, plugins, files, names),
    ),
)
