package co.xenastudios.xplots.reset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    @Test
    void roundTrips() throws IOException {
        int[] indices = new int[5 * 10 * 5];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i < 25 ? 1 : i % 7 == 0 ? 2 : 0;
        }
        Snapshot snapshot = new Snapshot(5, 10, 5, List.of("minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=north]"), indices);
        byte[] bytes = snapshot.encode();
        Snapshot read = Snapshot.decode(bytes);
        assertEquals(snapshot.palette(), read.palette());
        assertArrayEquals(indices, read.indices());
        assertEquals(10, read.height());
        // Deflated: a small plot is a few dozen bytes.
        assertEquals(true, bytes.length < 200, bytes.length + " bytes");
    }

    @Test
    void refusesWhatIsntOne() {
        assertThrows(IOException.class, () -> Snapshot.decode(new byte[] {1, 2, 3}));
    }
}
