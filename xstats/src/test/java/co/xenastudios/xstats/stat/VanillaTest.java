package co.xenastudios.xstats.stat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xstats.api.StatUnit;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Statistic;
import org.junit.jupiter.api.Test;

/**
 * The translation table, without a server. Real servers check it against CraftBukkit's
 * own mapping (/xstats status in boot-checks.txt) on 1.21.11 and 26.3.
 */
class VanillaTest {

    @Test
    void everyRenamedKeyIsABukkitStat() {
        for (Map.Entry<String, String> entry : Vanilla.RENAMED.entrySet()) {
            Statistic statistic = Vanilla.statistic(entry.getKey());
            assertNotNull(statistic, entry.getKey());
            assertEquals(entry.getValue(), statistic.name());
        }
    }

    @Test
    void everyUntypedBukkitStatHasOneVanillaKey() {
        Set<Statistic> reached = new HashSet<>();
        Map<String, String> back = new java.util.HashMap<>();
        Vanilla.RENAMED.forEach((key, name) -> back.put(name, key));
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() != Statistic.Type.UNTYPED) {
                continue;
            }
            String key = back.getOrDefault(statistic.name(), statistic.name().toLowerCase(Locale.ROOT));
            assertEquals(statistic, Vanilla.statistic(key), key);
            reached.add(statistic);
        }
        assertTrue(reached.size() >= 77, "1.21.11 has 77 untyped stats: " + reached.size());
    }

    @Test
    void termsAreWrittenAsScoreboardNamesThem() {
        assertEquals(new Vanilla.Term(Vanilla.Category.CUSTOM, "play_time"), Vanilla.term("minecraft:play_time"));
        assertEquals(new Vanilla.Term(Vanilla.Category.CUSTOM, "play_time"), Vanilla.term("play_one_minute"));
        assertEquals(new Vanilla.Term(Vanilla.Category.MINED, "*"), Vanilla.term(" mined:* "));
        assertEquals(new Vanilla.Term(Vanilla.Category.KILLED, "zombie"), Vanilla.term("killed:minecraft:zombie"));
        // 26.x's straw bed: accepted on 1.21.11 too, where it reads 0.
        assertEquals(new Vanilla.Term(Vanilla.Category.CUSTOM, "sleep_in_straw_bed"), Vanilla.term("sleep_in_straw_bed"));
        assertThrows(IllegalArgumentException.class, () -> Vanilla.term("not_a_stat"));
        assertThrows(IllegalArgumentException.class, () -> Vanilla.term("eaten:bread"));
        assertThrows(IllegalArgumentException.class, () -> Vanilla.term("mined:<script>"));
    }

    @Test
    void unitsComeFromTheKey() {
        assertEquals(StatUnit.TIME, Vanilla.unit(Vanilla.term("play_time")));
        assertEquals(StatUnit.DISTANCE, Vanilla.unit(Vanilla.term("aviate_one_cm")));
        assertEquals(StatUnit.DAMAGE, Vanilla.unit(Vanilla.term("damage_dealt")));
        assertEquals(StatUnit.COUNT, Vanilla.unit(Vanilla.term("mined:stone")));
        assertEquals(StatUnit.COUNT, Vanilla.unit(Vanilla.term("jump")));
    }

    @Test
    void filesAreReadInTheSameUnits() {
        Map<String, Map<String, Long>> file = Map.of(
            "minecraft:custom", Map.of("minecraft:play_one_minute", 72_000L, "minecraft:jump", 12L),
            "minecraft:mined", Map.of("minecraft:stone", 40L, "minecraft:dirt", 2L));
        assertEquals(3600, Vanilla.fromFile(Vanilla.term("play_time"), file), "ticks become seconds, under its old name");
        assertEquals(12, Vanilla.fromFile(Vanilla.term("jump"), file));
        assertEquals(42, Vanilla.fromFile(Vanilla.term("mined:*"), file));
        assertEquals(40, Vanilla.fromFile(Vanilla.term("mined:stone"), file));
        assertEquals(0, Vanilla.fromFile(Vanilla.term("killed:zombie"), file));
    }

    @Test
    void numbersReadAsPlayersDo() {
        assertEquals("12,345", Format.value(StatUnit.COUNT, 0, 12_345));
        assertEquals("12.34", Format.value(StatUnit.COUNT, 2, 1234));
        assertEquals("45s", Format.time(45));
        assertEquals("3h 12m", Format.time(3 * 3600 + 12 * 60));
        assertEquals("2d 4h", Format.time(2 * 86400 + 4 * 3600));
        assertEquals("350 m", Format.distance(35_000));
        assertEquals("1.2 km", Format.distance(120_000));
        assertEquals("1 heart", Format.value(StatUnit.DAMAGE, 0, 20));
        assertEquals("2.50", Format.ratio(5, 2));
        assertEquals("5.00", Format.ratio(5, 0));
    }
}
