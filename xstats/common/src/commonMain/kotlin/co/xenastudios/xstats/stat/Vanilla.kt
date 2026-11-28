package co.xenastudios.xstats.stat

import co.xenastudios.xstats.api.StatUnit

/**
 * Minecraft's statistics, as its `stats/<uuid>.json` files and `/scoreboard` name them
 * (`play_time`, `mined:stone`), and how Bukkit names the custom ones: 23 differently
 * (`PLAY_ONE_MINUTE`), the rest as their key in upper case. The same on every platform; each
 * adapter reads the stats its own way ([VanillaReader]).
 *
 * The custom stats are every one Minecraft has had from 1.13 to 26.x: a stat a server's version
 * doesn't have reads 0 there (`happy_ghast_one_cm` before 1.21.6). Units are vanilla's: distances
 * in centimetres, damage in tenths of a health point, and times in ticks, which xStats keeps in
 * seconds.
 */
public object Vanilla {
    /** Vanilla key to Bukkit name, where they differ. Every other key is its Bukkit name in lowercase. */
    public val RENAMED: Map<String, String> = mapOf(
        "clean_armor" to "ARMOR_CLEANED",
        "clean_banner" to "BANNER_CLEANED",
        "interact_with_beacon" to "BEACON_INTERACTION",
        "interact_with_brewingstand" to "BREWINGSTAND_INTERACTION",
        "eat_cake_slice" to "CAKE_SLICES_EATEN",
        "fill_cauldron" to "CAULDRON_FILLED",
        "use_cauldron" to "CAULDRON_USED",
        "open_chest" to "CHEST_OPENED",
        "interact_with_crafting_table" to "CRAFTING_TABLE_INTERACTION",
        "inspect_dispenser" to "DISPENSER_INSPECTED",
        "inspect_dropper" to "DROPPER_INSPECTED",
        "drop" to "DROP_COUNT",
        "open_enderchest" to "ENDERCHEST_OPENED",
        "pot_flower" to "FLOWER_POTTED",
        "interact_with_furnace" to "FURNACE_INTERACTION",
        "inspect_hopper" to "HOPPER_INSPECTED",
        "enchant_item" to "ITEM_ENCHANTED",
        "play_noteblock" to "NOTEBLOCK_PLAYED",
        "tune_noteblock" to "NOTEBLOCK_TUNED",
        "play_time" to "PLAY_ONE_MINUTE",
        "play_record" to "RECORD_PLAYED",
        "open_shulker_box" to "SHULKER_BOX_OPENED",
        "trigger_trapped_chest" to "TRAPPED_CHEST_TRIGGERED",
    )

    /**
     * Older keys, as the newest name. A stats file is only brought up to date when its player next
     * joins, and 1.13 to 1.16 servers still call it `play_one_minute`.
     */
    public val OLD_KEYS: Map<String, String> = mapOf("play_one_minute" to "play_time")

    /** Counted in ticks; kept in seconds. */
    public val TICKS: Set<String> = setOf("play_time", "total_world_time", "time_since_death", "time_since_rest", "sneak_time")

    /** Minecraft's stats that go back to 0 by themselves, so they can't be added up as counters. */
    public val NOT_COUNTERS: Set<String> = setOf("time_since_death", "time_since_rest")

    /** Every custom stat Minecraft has had from 1.13 on, by its newest key. */
    public val CUSTOM: Set<String> = (
        "leave_game play_time total_world_time time_since_death time_since_rest sneak_time walk_one_cm crouch_one_cm " +
            "sprint_one_cm walk_on_water_one_cm fall_one_cm climb_one_cm fly_one_cm walk_under_water_one_cm minecart_one_cm " +
            "boat_one_cm pig_one_cm happy_ghast_one_cm horse_one_cm aviate_one_cm swim_one_cm strider_one_cm nautilus_one_cm " +
            "jump drop damage_dealt damage_dealt_absorbed damage_dealt_resisted damage_taken damage_blocked_by_shield " +
            "damage_absorbed damage_resisted deaths mob_kills animals_bred player_kills fish_caught talked_to_villager " +
            "traded_with_villager eat_cake_slice fill_cauldron use_cauldron clean_armor clean_banner clean_shulker_box " +
            "interact_with_brewingstand interact_with_beacon inspect_dropper inspect_hopper inspect_dispenser play_noteblock " +
            "tune_noteblock pot_flower trigger_trapped_chest open_enderchest enchant_item play_record interact_with_furnace " +
            "interact_with_crafting_table open_chest sleep_in_bed open_shulker_box open_barrel interact_with_blast_furnace " +
            "interact_with_smoker interact_with_lectern interact_with_campfire interact_with_cartography_table interact_with_loom " +
            "interact_with_stonecutter bell_ring raid_trigger raid_win interact_with_anvil interact_with_grindstone target_hit " +
            "interact_with_smithing_table sleep_in_straw_bed"
        ).split(' ').toSet()

    /** The typed stats: vanilla's category, and what it counts. */
    public enum class Category(
        /** The category's name in stats files, without `minecraft:`. */
        public val key: String,
        public val of: Of,
    ) {
        CUSTOM("custom", Of.NONE),
        MINED("mined", Of.BLOCK),
        CRAFTED("crafted", Of.ITEM),
        USED("used", Of.ITEM),
        BROKEN("broken", Of.ITEM),
        PICKED_UP("picked_up", Of.ITEM),
        DROPPED("dropped", Of.ITEM),
        KILLED("killed", Of.ENTITY),
        KILLED_BY("killed_by", Of.ENTITY),
        ;

        /** What a category's stats are of. */
        public enum class Of { NONE, BLOCK, ITEM, ENTITY }
    }

    /** One vanilla stat, or every stat of a category ([name] is `*`). [name] has no `minecraft:`. */
    public class Term(public val category: Category, public val name: String) {
        /** All of a category, such as every block mined. */
        public val all: Boolean get() = name == "*"

        override fun equals(other: Any?): Boolean = other is Term && other.category == category && other.name == name

        override fun hashCode(): Int = category.hashCode() * 31 + name.hashCode()

        override fun toString(): String = if (category == Category.CUSTOM) name else category.key + ":" + name
    }

    private val ITEM_NAME = Regex("[a-z0-9_./]+")

    /**
     * One term as the config writes it: `play_time`, `mined:stone`, `mined:*`, `killed:zombie`, each
     * with or without `minecraft:`. Throws [IllegalArgumentException] with what's wrong.
     */
    public fun term(raw: String): Term {
        val text = strip(raw.trim().lowercase())
        val colon = text.indexOf(':')
        if (colon < 0) {
            val key = OLD_KEYS[text] ?: text
            require(key in CUSTOM) { "\"${raw.trim()}\" isn't one of Minecraft's stats" }
            return Term(Category.CUSTOM, key)
        }
        val categoryKey = text.substring(0, colon)
        val category = Category.entries.firstOrNull { it.key == categoryKey && it != Category.CUSTOM }
            ?: throw IllegalArgumentException(
                "\"${raw.trim()}\": the part before : must be mined, crafted, used, broken, picked_up, dropped, killed or killed_by",
            )
        val name = strip(text.substring(colon + 1))
        require(name == "*" || ITEM_NAME.matches(name)) { "\"${raw.trim()}\" isn't a block, item or mob name" }
        return Term(category, name)
    }

    private fun strip(key: String): String = key.removePrefix("minecraft:")

    /** What a term counts in, as kept: time in seconds, distance in centimetres, damage in tenths. */
    public fun unit(term: Term): StatUnit {
        if (term.category != Category.CUSTOM) return StatUnit.COUNT
        val name = term.name
        return when {
            name in TICKS -> StatUnit.TIME
            name.endsWith("_one_cm") -> StatUnit.DISTANCE
            name.startsWith("damage_") -> StatUnit.DAMAGE
            else -> StatUnit.COUNT
        }
    }

    /** The Bukkit `Statistic` constant's name for a custom stat. */
    public fun bukkitName(key: String): String = RENAMED[key] ?: key.uppercase()

    /** Names a custom stat had before, newest last, for a server older than the name: `play_one_minute` for `play_time`. */
    public fun olderKeys(key: String): List<String> {
        val out = ArrayList<String>(1)
        for ((old, new) in OLD_KEYS) if (new == key) out += old
        return out
    }

    /** A raw vanilla value in the unit xStats keeps: ticks become whole seconds. */
    public fun stored(term: Term, raw: Long): Long = if (term.category == Category.CUSTOM && term.name in TICKS) raw / 20 else raw

    /**
     * A term's value from a stats file's `stats` object, in the unit xStats keeps. A file's keys:
     * `minecraft:custom` → `minecraft:play_time` → 1234. Older names in the file count as the new ones.
     *
     * @param stats category key (with `minecraft:`) to key to value, as the file has them
     */
    public fun fromFile(term: Term, stats: Map<String, Map<String, Long>>): Long {
        val category = stats["minecraft:" + term.category.key] ?: return 0
        var sum = 0L
        if (term.all) {
            for (value in category.values) sum += value
        } else {
            for ((rawKey, value) in category) {
                var key = strip(rawKey)
                if (term.category == Category.CUSTOM) key = OLD_KEYS[key] ?: key
                if (key == term.name) sum += value
            }
        }
        return stored(term, sum)
    }
}

/**
 * How an adapter reads Minecraft's stats from its players: Bukkit's `Player#getStatistic`, Fabric's
 * stat counter. [prepare] turns a stat's terms into something quick to read, once per config.
 */
public interface VanillaReader {
    /** [terms] ready to read from a player, or why some of them aren't on this server (each read as 0). */
    public fun prepare(terms: List<Vanilla.Term>): Prepared

    /** Terms ready to read. */
    public interface Prepared {
        /** Terms this server doesn't have, which read 0, for one warning. */
        public val missing: List<Vanilla.Term>
    }
}
