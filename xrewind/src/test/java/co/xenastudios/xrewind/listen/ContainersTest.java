package co.xenastudios.xrewind.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** A container visit, compared before and after. */
class ContainersTest {

    @BeforeEach
    void start() {
        MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void movesBetweenSlotsCancelOutAndTakingAndAddingCount() {
        ItemStack[] before = {new ItemStack(Material.DIAMOND, 10), new ItemStack(Material.DIRT, 64), null};
        // Diamonds moved to another slot and 3 taken; dirt untouched; gold added.
        ItemStack[] after = {null, new ItemStack(Material.DIRT, 64), new ItemStack(Material.DIAMOND, 7)};
        after[0] = new ItemStack(Material.GOLD_INGOT, 2);
        Map<ItemStack, Integer> diff = Containers.diff(before, after);
        assertEquals(2, diff.size());
        assertEquals(-3, diff.get(new ItemStack(Material.DIAMOND)));
        assertEquals(2, diff.get(new ItemStack(Material.GOLD_INGOT)));
    }

    @Test
    void anUnchangedVisitLogsNothing() {
        ItemStack[] same = {new ItemStack(Material.STONE, 5)};
        assertEquals(0, Containers.diff(same, new ItemStack[] {new ItemStack(Material.STONE, 5)}).size());
    }
}
