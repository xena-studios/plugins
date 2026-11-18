package co.xenastudios.xrewind.rollback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.query.Row;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PlannerTest {

    @BeforeEach
    void start() {
        MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private static Row row(long id, int x, String before, String after) {
        return new Row(Row.Table.BLOCK, id, (int) id, "Steve", "world", x, 64, 0, Actions.PLACE, before, after, false, 0, null);
    }

    // At x=0: stone placed, broken, then dirt placed. At x=1: glass placed once.
    private final List<Row> rows = List.of(
        row(1, 0, "minecraft:air", "minecraft:stone"),
        row(2, 1, "minecraft:air", "minecraft:glass"),
        row(3, 0, "minecraft:stone", "minecraft:air"),
        row(4, 0, "minecraft:air", "minecraft:dirt"));

    @Test
    void aRollbackSetsEachBlockOnceToHowItWasFirst() {
        Plan plan = Planner.plan(rows, List.of(), false);
        assertEquals(2, plan.changes().size(), "four changes, two blocks");
        assertEquals(Material.AIR, plan.changes().get(0).target().getMaterial());
        assertEquals(Material.AIR, plan.changes().get(1).target().getMaterial());
        assertArrayEquals(new long[] {1, 3, 4}, plan.changes().get(0).ids(), "x=0's three rows");
        assertArrayEquals(new long[] {2}, plan.changes().get(1).ids());
        assertEquals(true, plan.markRolledBack());
    }

    @Test
    void aRestoreSetsEachBlockToHowItWasLast() {
        Plan plan = Planner.plan(rows, List.of(), true);
        assertEquals(Material.DIRT, plan.changes().get(0).target().getMaterial());
        assertEquals(Material.GLASS, plan.changes().get(1).target().getMaterial());
        assertEquals(false, plan.markRolledBack());
    }

    @Test
    void anOldStateFallsBackToItsBlockAndAGoneBlockIsSkipped() {
        assertEquals(Material.STONE, Planner.read("minecraft:stone[made_up=true]").getMaterial());
        assertNull(Planner.read("minecraft:no_such_block"));
        Plan plan = Planner.plan(List.of(row(1, 0, "minecraft:no_such_block", "minecraft:stone")), List.of(), false);
        assertEquals(0, plan.changes().size());
        assertEquals(1, plan.skipped());
    }
}
