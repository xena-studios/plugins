package co.xenastudios.xstats.stat;

import co.xenastudios.xstats.api.StatUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * The one translation table between Minecraft's statistics, as its {@code stats/*.json}
 * files and {@code /scoreboard} name them ({@code play_time}, {@code mined:stone}), and
 * Bukkit's {@link Statistic} constants, which name 23 of them differently
 * ({@code PLAY_ONE_MINUTE}). Everything that reads a vanilla stat, live or from a file,
 * goes through here.
 *
 * <p>Checked against CraftBukkit's own mapping on Paper 1.21.11 and 26.3: the same 78
 * custom stats, plus 26.x's {@code sleep_in_straw_bed}, which a 1.21.11 server doesn't
 * have (a source naming it there reads 0, with one warning).
 *
 * <p>Units are vanilla's: distances in centimetres, damage in tenths of a health point,
 * and times in ticks, which this table turns into seconds.
 */
public final class Vanilla {

    /** Vanilla key to Bukkit name, where they differ. Every other key is its Bukkit name in lowercase. */
    static final Map<String, String> RENAMED = Map.ofEntries(
        Map.entry("clean_armor", "ARMOR_CLEANED"),
        Map.entry("clean_banner", "BANNER_CLEANED"),
        Map.entry("interact_with_beacon", "BEACON_INTERACTION"),
        Map.entry("interact_with_brewingstand", "BREWINGSTAND_INTERACTION"),
        Map.entry("eat_cake_slice", "CAKE_SLICES_EATEN"),
        Map.entry("fill_cauldron", "CAULDRON_FILLED"),
        Map.entry("use_cauldron", "CAULDRON_USED"),
        Map.entry("open_chest", "CHEST_OPENED"),
        Map.entry("interact_with_crafting_table", "CRAFTING_TABLE_INTERACTION"),
        Map.entry("inspect_dispenser", "DISPENSER_INSPECTED"),
        Map.entry("inspect_dropper", "DROPPER_INSPECTED"),
        Map.entry("drop", "DROP_COUNT"),
        Map.entry("open_enderchest", "ENDERCHEST_OPENED"),
        Map.entry("pot_flower", "FLOWER_POTTED"),
        Map.entry("interact_with_furnace", "FURNACE_INTERACTION"),
        Map.entry("inspect_hopper", "HOPPER_INSPECTED"),
        Map.entry("enchant_item", "ITEM_ENCHANTED"),
        Map.entry("play_noteblock", "NOTEBLOCK_PLAYED"),
        Map.entry("tune_noteblock", "NOTEBLOCK_TUNED"),
        Map.entry("play_time", "PLAY_ONE_MINUTE"),
        Map.entry("play_record", "RECORD_PLAYED"),
        Map.entry("open_shulker_box", "SHULKER_BOX_OPENED"),
        Map.entry("trigger_trapped_chest", "TRAPPED_CHEST_TRIGGERED"));

    /**
     * Keys older stats files use, as the newest name. A file is only brought up to date
     * when its player next joins, so players who haven't joined since 1.16 still have
     * {@code play_one_minute}.
     */
    static final Map<String, String> OLD_KEYS = Map.of(
        "play_one_minute", "play_time");

    /** Counted in ticks; stored in seconds. */
    static final Set<String> TICKS = Set.of("play_time", "total_world_time", "time_since_death", "time_since_rest", "sneak_time");

    /** The typed stats: vanilla's category, Bukkit's constant, and what it counts. */
    public enum Category {
        CUSTOM("custom", null, Of.NONE),
        MINED("mined", Statistic.MINE_BLOCK, Of.BLOCK),
        CRAFTED("crafted", Statistic.CRAFT_ITEM, Of.ITEM),
        USED("used", Statistic.USE_ITEM, Of.ITEM),
        BROKEN("broken", Statistic.BREAK_ITEM, Of.ITEM),
        PICKED_UP("picked_up", Statistic.PICKUP, Of.ITEM),
        DROPPED("dropped", Statistic.DROP, Of.ITEM),
        KILLED("killed", Statistic.KILL_ENTITY, Of.ENTITY),
        KILLED_BY("killed_by", Statistic.ENTITY_KILLED_BY, Of.ENTITY);

        enum Of { NONE, BLOCK, ITEM, ENTITY }

        final String key;
        final Statistic statistic;
        final Of of;

        Category(String key, Statistic statistic, Of of) {
            this.key = key;
            this.statistic = statistic;
            this.of = of;
        }

        /** The category's name in stats files, without {@code minecraft:}. */
        public String key() {
            return key;
        }

        static Category byKey(String key) {
            for (Category category : values()) {
                if (category.key.equals(key)) {
                    return category;
                }
            }
            return null;
        }
    }

    /**
     * One vanilla stat, or every stat of a category ({@code name} is {@code *}).
     *
     * @param name the key without {@code minecraft:}: {@code play_time}, {@code stone}, {@code zombie}
     */
    public record Term(Category category, String name) {

        /** All of a category, such as every block mined. */
        public boolean all() {
            return name.equals("*");
        }

        @Override
        public String toString() {
            return category == Category.CUSTOM ? name : category.key + ":" + name;
        }
    }

    private Vanilla() {}

    /**
     * Parses one term as the config writes it: {@code play_time}, {@code mined:stone},
     * {@code mined:*}, {@code killed:zombie}, each with or without {@code minecraft:}.
     *
     * @throws IllegalArgumentException with what's wrong
     */
    public static Term term(String raw) {
        String text = strip(raw.strip().toLowerCase(Locale.ROOT));
        int colon = text.indexOf(':');
        if (colon < 0) {
            String key = OLD_KEYS.getOrDefault(text, text);
            if (statistic(key) == null && !key.equals("sleep_in_straw_bed")) {
                throw new IllegalArgumentException("\"" + raw.strip() + "\" isn't one of Minecraft's stats");
            }
            return new Term(Category.CUSTOM, key);
        }
        Category category = Category.byKey(text.substring(0, colon));
        String name = strip(text.substring(colon + 1));
        if (category == null || category == Category.CUSTOM) {
            throw new IllegalArgumentException("\"" + raw.strip() + "\": the part before : must be mined, crafted, used, broken,"
                + " picked_up, dropped, killed or killed_by");
        }
        if (!name.equals("*") && !name.matches("[a-z0-9_./]+")) {
            throw new IllegalArgumentException("\"" + raw.strip() + "\" isn't a block, item or mob name");
        }
        return new Term(category, name);
    }

    private static String strip(String key) {
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    /** What a term counts in, as stored: time in seconds, distance in centimetres, damage in tenths. */
    public static StatUnit unit(Term term) {
        if (term.category() != Category.CUSTOM) {
            return StatUnit.COUNT;
        }
        String name = term.name();
        if (TICKS.contains(name)) {
            return StatUnit.TIME;
        }
        if (name.endsWith("_one_cm")) {
            return StatUnit.DISTANCE;
        }
        if (name.startsWith("damage_")) {
            return StatUnit.DAMAGE;
        }
        return StatUnit.COUNT;
    }

    /** The custom stat's Bukkit constant, or null if this server doesn't have it. */
    static Statistic statistic(String key) {
        String name = RENAMED.getOrDefault(key, key.toUpperCase(Locale.ROOT));
        try {
            Statistic statistic = Statistic.valueOf(name);
            return statistic.getType() == Statistic.Type.UNTYPED ? statistic : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A raw vanilla value in the unit xStats stores: ticks become whole seconds. */
    static long stored(Term term, long raw) {
        return term.category() == Category.CUSTOM && TICKS.contains(term.name()) ? raw / 20 : raw;
    }

    /**
     * A term made ready to read from players: its Bukkit constant and, for a typed stat,
     * the blocks, items or mobs to add up. Made once per config load.
     */
    public static final class Reader {
        private final Term term;
        private final Statistic statistic;
        private final Material[] materials;
        private final EntityType[] entities;

        private Reader(Term term, Statistic statistic, Material[] materials, EntityType[] entities) {
            this.term = term;
            this.statistic = statistic;
            this.materials = materials;
            this.entities = entities;
        }

        /** Whether this server has the stat. A 1.21.11 server has no {@code sleep_in_straw_bed}. */
        public boolean available() {
            return statistic != null;
        }

        /** The player's value, in the unit xStats stores. Call on the player's own thread. */
        public long read(Player player) {
            if (statistic == null) {
                return 0;
            }
            long sum = 0;
            switch (term.category().of) {
                case NONE -> sum = player.getStatistic(statistic);
                case BLOCK, ITEM -> {
                    for (Material material : materials) {
                        sum += player.getStatistic(statistic, material);
                    }
                }
                case ENTITY -> {
                    for (EntityType entity : entities) {
                        sum += player.getStatistic(statistic, entity);
                    }
                }
            }
            return stored(term, sum);
        }
    }

    /** Readies a term for {@link Reader#read}. Lists every block, item or mob once for a {@code *}. */
    public static Reader reader(Term term) {
        Category category = term.category();
        if (category == Category.CUSTOM) {
            return new Reader(term, statistic(term.name()), null, null);
        }
        return switch (category.of) {
            case BLOCK, ITEM -> {
                List<Material> materials = new ArrayList<>();
                for (Material material : Material.values()) {
                    if (material.isLegacy() || !(category.of == Category.Of.BLOCK ? material.isBlock() : material.isItem())) {
                        continue;
                    }
                    if (term.all() || material.getKey().getKey().equals(term.name())) {
                        materials.add(material);
                    }
                }
                yield new Reader(term, materials.isEmpty() ? null : category.statistic, materials.toArray(Material[]::new), null);
            }
            case ENTITY -> {
                List<EntityType> entities = new ArrayList<>();
                for (EntityType type : EntityType.values()) {
                    if (type == EntityType.UNKNOWN || !type.isAlive()) {
                        continue;
                    }
                    if (term.all() || type.getKey().getKey().equals(term.name())) {
                        entities.add(type);
                    }
                }
                yield new Reader(term, entities.isEmpty() ? null : category.statistic, null, entities.toArray(EntityType[]::new));
            }
            case NONE -> throw new IllegalStateException();
        };
    }

    /**
     * Checks this table against the server's own: Paper's scoreboard criteria name each
     * Bukkit stat by its vanilla key, through CraftBukkit's mapping. Every untyped Bukkit
     * stat must come back from its vanilla key, and each category must name its stats as
     * stats files do.
     *
     * @return what doesn't match; empty when the table is right for this server
     */
    public static List<String> verify() {
        List<String> wrong = new ArrayList<>();
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() != Statistic.Type.UNTYPED) {
                continue;
            }
            String name = org.bukkit.scoreboard.Criteria.statistic(statistic).getName();
            String prefix = "minecraft.custom:minecraft.";
            if (!name.startsWith(prefix)) {
                wrong.add(statistic + " is " + name);
                continue;
            }
            String key = name.substring(prefix.length());
            if (statistic(key) != statistic) {
                wrong.add(key + " should be " + statistic);
            }
        }
        for (Category category : Category.values()) {
            if (category == Category.CUSTOM) {
                continue;
            }
            String name = category.of == Category.Of.ENTITY
                ? org.bukkit.scoreboard.Criteria.statistic(category.statistic, EntityType.ZOMBIE).getName()
                : org.bukkit.scoreboard.Criteria.statistic(category.statistic, category.of == Category.Of.BLOCK ? Material.STONE
                    : Material.STONE_PICKAXE).getName();
            String expected = "minecraft." + category.key + ":minecraft."
                + (category.of == Category.Of.ENTITY ? "zombie" : category.of == Category.Of.BLOCK ? "stone" : "stone_pickaxe");
            if (!name.equals(expected)) {
                wrong.add(category.key + " is " + name);
            }
        }
        return wrong;
    }

    /** How many of Minecraft's untyped stats this server has, for /xstats status. */
    public static int untyped() {
        int count = 0;
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED) {
                count++;
            }
        }
        return count;
    }

    /**
     * A term's value from a stats file's {@code stats} object, in the unit xStats stores.
     * Keys a file can have: {@code minecraft:custom} → {@code minecraft:play_time} → 1234.
     * Older names in the file ({@code play_one_minute}) count as their new ones.
     *
     * @param stats category key (with {@code minecraft:}) to key to value, as the file has them
     */
    public static long fromFile(Term term, Map<String, Map<String, Long>> stats) {
        Map<String, Long> category = stats.get("minecraft:" + term.category().key);
        if (category == null) {
            return 0;
        }
        long sum = 0;
        if (term.all()) {
            for (long value : category.values()) {
                sum += value;
            }
        } else {
            for (Map.Entry<String, Long> entry : category.entrySet()) {
                String key = strip(entry.getKey());
                if (term.category() == Category.CUSTOM) {
                    key = OLD_KEYS.getOrDefault(key, key);
                }
                if (key.equals(term.name())) {
                    sum += entry.getValue();
                }
            }
        }
        return stored(term, sum);
    }
}
