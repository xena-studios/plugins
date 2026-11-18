package co.xenastudios.xplots.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xplots.model.Box;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Layout;
import co.xenastudios.xplots.model.Lease;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.model.Plots;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Everything saved comes back after a restart, as it was. */
class StoreTest {

    @TempDir
    Path folder;

    private final Logger logger = Logger.getLogger("StoreTest");
    private final StorageSettings settings = StorageSettings.defaults("xplots_");

    @Test
    void fieldsPlotsLeasesAndSnapshotsSurviveARestart() throws Exception {
        Store store = Store.open(settings, folder, logger);
        Plots plots = store.load();
        assertTrue(plots.isEmpty());

        Layout layout = Layout.of(Box.of(0, 64, 0, 11, 70, 4), 5, 5, 2);
        Field field = new Field(1, "box", "world", layout);
        for (Layout.Spot spot : layout.spots()) {
            field.add(new Plot(field, spot.number(), spot.box(), spot.signX(), spot.signY(), spot.signZ(), spot.facing()));
        }
        field.override("price", "250");
        field.warp("1.50,65.00,2.50,90.0,0.0");
        plots.index(field);
        store.saveField(field);
        store.updateField(field);

        Plot first = field.plots().get(0);
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        Lease lease = new Lease(steve, "Steve", 1_900_000_000_000L, 2, Lease.WARNED_DAY);
        lease.members().put(alex, "Alex");
        plots.lease(first, lease);
        store.saveLease(first);
        store.saveSnapshot(first, new byte[] {1, 2, 3});

        Plot custom = new Plot(field, field.nextNumber(), Box.of(20, 64, 0, 22, 66, 2), 21, 65, 3, BlockFace.SOUTH);
        plots.add(custom);
        store.savePlot(custom);
        plots.moveSign(custom, 23, 65, 1, BlockFace.EAST);
        store.savePlot(custom);

        // Ordered after the writes before it.
        CompletableFuture<byte[]> snapshot = new CompletableFuture<>();
        store.loadSnapshot(first, snapshot::complete);
        assertArrayEquals(new byte[] {1, 2, 3}, snapshot.get(10, TimeUnit.SECONDS));
        store.close(10, TimeUnit.SECONDS);

        Store again = Store.open(settings, folder, logger);
        Plots loaded = again.load();
        Field box = loaded.field("box");
        assertEquals(3, box.plots().size());
        assertEquals("250", box.overrides().get("price"));
        assertEquals("1.50,65.00,2.50,90.0,0.0", box.warp());
        Plot back = loaded.plot("box-1");
        assertEquals(steve, back.lease().owner());
        assertEquals(1_900_000_000_000L, back.lease().expires());
        assertEquals(2, back.lease().slots());
        assertEquals(Lease.WARNED_DAY, back.lease().warned());
        assertEquals("Alex", back.lease().members().get(alex));
        assertEquals(1, loaded.owned(steve).size());
        Plot movedSign = loaded.plot("box-3");
        assertEquals(23, movedSign.signX());
        assertEquals(BlockFace.EAST, movedSign.signFacing());
        assertNull(loaded.plot("box-2").lease());

        // Ending a lease and deleting a field take their rows with them.
        loaded.lease(back, null);
        again.saveLease(back);
        again.deleteField(box);
        again.close(10, TimeUnit.SECONDS);
        Store last = Store.open(settings, folder, logger);
        assertTrue(last.load().isEmpty());
        last.close(10, TimeUnit.SECONDS);
    }
}
