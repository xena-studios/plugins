package co.xenastudios.xmarket.price

import co.xenastudios.xmarket.api.Money

/**
 * Makes xMarket's default price list from xmarket/prices/: every item of the newest Minecraft
 * version (its registry's order, which keeps families together) but the ones no survival player
 * can get, each potion type as a potion, splash potion, lingering potion and tipped arrow, and
 * every enchanted book; each priced from [base] and the recipes, and put in a category.
 *
 * - **Buy price:** the cheapest way to make the item from items with a base price, through any
 *   chain of recipes ([Pricing.costs]), rounded up to a cent.
 * - **Sell price:** [sellPercent] of the buy price, rounded down to a cent. So selling anything
 *   pays less than it costs to buy or make, and no buy-craft-sell loop can pay (the loop test
 *   checks it for every supported version's recipes).
 * - **Enchanted books:** a book, plus the levels the enchanting table asks for that level,
 *   priced [LEVEL] each, more for rarer enchantments and three times for treasure ones.
 *
 * The result is `shops/<category>.yml`, which the jar ships and the tests compare with this.
 */
public class Generator(
    /** The newest version's items.txt, in its order. */
    private val items: List<String>,
    private val base: Map<String, Money>,
    private val book: RecipeBook,
    private val excluded: Set<String>,
    private val sellOnly: Set<String>,
    /** enchantments.txt's rows. */
    private val enchantments: List<Enchantment>,
    private val sellPercent: Long = 30,
) {
    public class Enchantment(public val name: String, public val maxLevel: Int, public val weight: Int, public val minBase: Int, public val minPerLevel: Int, public val kind: String)

    /** The price list, and what couldn't be priced (an item with no base price and no recipe from priced items). */
    public class Result(public val categories: List<Category>, public val unpriced: List<String>, public val costs: Map<String, Money>)

    public fun generate(): Result {
        val keys = ArrayList<String>()
        for (item in items) {
            if (item.endsWith("_spawn_egg")) continue
            if (item !in excluded) keys += item
            // Each potion type after its item, from the brewing recipes.
            if (item in VARIANTS) keys += book.recipes.map { it.result }.filter { it.startsWith("$item{") }.distinct()
            if (item == "enchanted_book") for (e in enchantments) for (level in 1..e.maxLevel) keys += "enchanted_book{${e.name}=$level}"
        }
        val given = HashMap(base)
        val costs0 = Pricing.costs(given, book)
        val bookCost = costs0["book"]
        if (bookCost != null) {
            for (e in enchantments) for (level in 1..e.maxLevel) {
                given["enchanted_book{${e.name}=$level}"] = bookCost + enchantedBook(e, level)
            }
        }
        val costs = Pricing.costs(given, book)
        val unpriced = ArrayList<String>()
        val byCategory = LinkedHashMap<String, MutableList<Entry>>()
        for (c in CATEGORIES) byCategory[c.id] = ArrayList()
        for (key in keys) {
            val cost = costs[key]
            if (cost == null) {
                unpriced += key
                continue
            }
            val buy = Money.ofUnits(roundUp(cost.units))
            val sell = Money.ofUnits(roundDown(buy.units * sellPercent / 100)).takeIf { !it.isZero } ?: Money.ofUnits(CENT)
            val entry = Entry(key, ItemSpec.ofKey(key), if (key in sellOnly) null else buy, sell)
            byCategory.getValue(categoryOf(key)) += entry
        }
        val categories = CATEGORIES.mapNotNull { c ->
            val entries = byCategory.getValue(c.id)
            if (entries.isEmpty()) null else Category(c.id, c.name, ItemSpec.ofKey(c.icon), c.slot, Currency.Money, null, emptyList(), entries)
        }
        return Result(categories, unpriced, costs)
    }

    private fun enchantedBook(e: Enchantment, level: Int): Money {
        val levels = e.minBase + e.minPerLevel * (level - 1)
        // Rarer at the table, dearer: weight 10 is common, 1 the rarest.
        val rarity = when {
            e.weight >= 10 -> 100L
            e.weight >= 5 -> 150L
            e.weight >= 2 -> 250L
            else -> 400L
        }
        val kind = when (e.kind) {
            "treasure" -> 300L
            "curse" -> 20L
            else -> 100L
        }
        return Money.of(levels.toLong() * LEVEL).percent(rarity).percent(kind)
    }

    private class Shelf(val id: String, val name: String, val icon: String, val slot: Int)

    public companion object {
        private const val CENT = 100L

        /** What one enchanting-table level is worth, for enchanted books. */
        public const val LEVEL: Long = 10

        private val VARIANTS = setOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

        private fun roundUp(units: Long): Long = (units + CENT - 1) / CENT * CENT
        private fun roundDown(units: Long): Long = units / CENT * CENT

        private val CATEGORIES = listOf(
            Shelf("building", "<gold>Building blocks", "bricks", 10),
            Shelf("wood", "<gold>Wood", "oak_log", 11),
            Shelf("colors", "<light_purple>Colours", "cyan_wool", 12),
            Shelf("nature", "<green>Nature", "oak_sapling", 13),
            Shelf("ores", "<aqua>Ores & minerals", "diamond", 14),
            Shelf("farming", "<yellow>Farming & food", "wheat", 15),
            Shelf("mobs", "<red>Mob drops", "bone", 16),
            Shelf("redstone", "<red>Redstone", "redstone", 19),
            Shelf("tools", "<white>Tools & weapons", "iron_pickaxe", 20),
            Shelf("armor", "<white>Armour", "iron_chestplate", 21),
            Shelf("brewing", "<light_purple>Brewing", "brewing_stand", 22),
            Shelf("enchanting", "<dark_purple>Enchanting", "enchanting_table", 23),
            Shelf("nether", "<dark_red>Nether", "netherrack", 24),
            Shelf("end", "<dark_purple>The End", "end_stone", 25),
            Shelf("decoration", "<gold>Decoration", "flower_pot", 28),
            Shelf("misc", "<gray>Miscellaneous", "music_disc_cat", 29),
        )

        private const val COLORS = "white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black"
        private const val WOODS = "oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|pale_oak|poplar|bamboo|crimson|warped"

        // First match wins.
        private val RULES: List<Pair<String, Regex>> = listOf(
            "brewing" to Regex("""^(potion|splash_potion|lingering_potion)(\{.*)?$|^(brewing_stand|cauldron|glass_bottle|blaze_powder|magma_cream|fermented_spider_eye|glistering_melon_slice|dragon_breath|nether_wart|ghast_tear)$"""),
            "tools" to Regex("""^tipped_arrow.*|.*_(sword|pickaxe|axe|shovel|hoe|spear)$|^(bow|crossbow|arrow|spectral_arrow|trident|mace|shield|fishing_rod|flint_and_steel|shears|compass|recovery_compass|clock|spyglass|brush|lead|name_tag|saddle|carrot_on_a_stick|warped_fungus_on_a_stick|bucket|wind_charge|fire_charge|snowball|egg|ender_eye|map|bundle|.*_bundle|.*_harness)$"""),
            "armor" to Regex(""".*_(helmet|chestplate|leggings|boots|horse_armor|nautilus_armor)$|^(elytra|wolf_armor|totem_of_undying)$"""),
            "enchanting" to Regex("""^enchanted_book.*|^(enchanting_table|bookshelf|chiseled_bookshelf|experience_bottle|book|anvil|chipped_anvil|damaged_anvil|grindstone)$"""),
            "colors" to Regex("""^($COLORS)_(wool|carpet|concrete|concrete_powder|concrete_slab|concrete_stairs|terracotta|glazed_terracotta|stained_glass|stained_glass_pane|dye|bed|banner|candle|shulker_box|wool_slab|wool_stairs|cushion)$|^(terracotta|glass|glass_pane|tinted_glass|candle|shulker_box)$"""),
            "redstone" to Regex("""^(redstone|redstone_block|redstone_torch|repeater|comparator|piston|sticky_piston|observer|dispenser|dropper|hopper|lever|tripwire_hook|daylight_detector|target|note_block|redstone_lamp|rail|powered_rail|detector_rail|activator_rail|tnt|lectern|calibrated_sculk_sensor|sculk_sensor|crafter|slime_block|honey_block|trapped_chest|jukebox|lightning_rod)$|.*(_button|_pressure_plate|minecart|_bulb|_lightning_rod)$"""),
            "ores" to Regex(""".*_ore$|^raw_.*|.*_(ingot|nugget)$|^(coal|charcoal|diamond|emerald|lapis_lazuli|quartz|amethyst_shard|netherite_scrap|ancient_debris|coal_block|iron_block|gold_block|diamond_block|emerald_block|lapis_block|netherite_block|copper_block|amethyst_block|budding_amethyst|.*_amethyst_bud|amethyst_cluster|glowstone_dust|flint|echo_shard)$"""),
            "mobs" to Regex("""^(rotten_flesh|bone|bone_meal|bone_block|string|spider_eye|gunpowder|ender_pearl|blaze_rod|breeze_rod|slime_ball|leather|feather|rabbit_hide|rabbit_foot|phantom_membrane|shulker_shell|nether_star|ink_sac|glow_ink_sac|prismarine_shard|prismarine_crystals|armadillo_scute|turtle_scute|turtle_egg|sniffer_egg|nautilus_shell|heart_of_the_sea|honeycomb|goat_horn|heavy_core|wet_sponge|sponge|.*_skull|.*_head|scute|copper_golem_statue)$"""),
            "farming" to Regex("""^(wheat|wheat_seeds|beetroot|beetroot_seeds|carrot|potato|baked_potato|poisonous_potato|melon|melon_slice|melon_seeds|pumpkin|pumpkin_seeds|carved_pumpkin|jack_o_lantern|sugar_cane|sugar|cocoa_beans|sweet_berries|glow_berries|apple|golden_apple|enchanted_golden_apple|golden_carrot|bread|cookie|cake|pumpkin_pie|mushroom_stew|rabbit_stew|beetroot_soup|suspicious_stew|honey_bottle|dried_kelp|dried_kelp_block|hay_block|milk_bucket|blue_egg|brown_egg|torchflower_seeds|pitcher_pod|beef|porkchop|chicken|mutton|rabbit|cod|salmon|pufferfish|tropical_fish|composter|beehive|bee_nest|bowl|.*_bucket)$|^cooked_.*"""),
            "nether" to Regex(""".*(nether|crimson|warped|soul|basalt|blackstone|quartz|magma|shroomlight|glowstone|wither_rose|weeping|twisting|gilded|ghast|piglin|lodestone|respawn_anchor|crying_obsidian|obsidian).*"""),
            "end" to Regex(""".*(end_stone|purpur|chorus|end_rod|end_crystal|ender_chest|dragon|shulker).*"""),
            "nature" to Regex(""".*(_sapling|_leaves|_propagule)$"""),
            "wood" to Regex("""^($WOODS)_.*|^stripped_.*|.*_(planks|log|wood|stem|hyphae)$|^(stick|bamboo|bamboo_mosaic.*|chest|crafting_table|barrel|ladder|scaffolding)$"""),
            "nature" to Regex(""".*(_sapling|_leaves|_coral.*|_tulip|_mushroom.*|_fungus|_roots|_propagule|_nylium|_petals|_grass|_fern|_bush|_vines?|_lichen|_dripleaf|_flower|_froglight|sculk.*)$|^(dirt|coarse_dirt|rooted_dirt|grass_block|podzol|mycelium|mud|muddy_mangrove_roots|clay|clay_ball|gravel|sand|red_sand|moss_block|pale_moss_block|pale_hanging_moss|moss_carpet|pale_moss_carpet|snow|snow_block|ice|packed_ice|blue_ice|cactus|kelp|seagrass|sea_pickle|lily_pad|cobweb|vine|dandelion|poppy|blue_orchid|allium|azure_bluet|oxeye_daisy|cornflower|lily_of_the_valley|sunflower|lilac|rose_bush|peony|torchflower|pitcher_plant|open_eyeblossom|closed_eyeblossom|azalea|flowering_azalea|spore_blossom|hanging_roots|dead_bush|bush|wildflowers|red_shrub|shelf_mushroom|leaf_litter|cactus_flower|firefly_bush|pointed_dripstone|dripstone_block|cinnabar|sulfur_spike)$"""),
            "decoration" to Regex(""".*(_sign|_hanging_sign|_banner_pattern|_pottery_sherd|_carpet|lantern|torch|campfire|_candle|_chain|_bars)$|^(flower_pot|decorated_pot|painting|item_frame|glow_item_frame|armor_stand|bell|chain|iron_bars|loom|cartography_table|fletching_table|smithing_table|stonecutter|furnace|smoker|blast_furnace|conduit|beacon|end_portal_frame|heavy_weighted_pressure_plate|copper_chest|shelf)$"""),
            "misc" to Regex(""".*(music_disc|disc_fragment|smithing_template|trial_key|ominous|banner_pattern)$|^(firework_rocket|paper|book|writable_book|nether_star|elytra)$"""),
        )

        /** The category an item goes in by default: `ores` for a diamond. Building blocks when nothing else fits. */
        public fun categoryOf(key: String): String {
            val id = key.substringBefore('{')
            for ((category, rule) in RULES) if (rule.matches(id)) return category
            return "building"
        }
    }
}
