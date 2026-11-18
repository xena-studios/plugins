package co.xenastudios.xteleport.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchTest {

    @Test
    void picksInTheRingSpreadOverItsArea() {
        Search.Area area = new Search.Area(100, -50, 300, 1000, 10, false, false, Set.of());
        Random random = new Random(1);
        int outer = 0;
        for (int i = 0; i < 10_000; i++) {
            int[] point = Search.pick(area, random);
            double distance = Math.hypot(point[0] - 100, point[1] + 50);
            assertTrue(distance >= 298 && distance <= 1002, "in the ring: " + distance);
            if (distance > 650) {
                outer++;
            }
        }
        // The outer part of the ring holds most of its area: (1000² - 650²) / (1000² - 300²) ≈ 63%.
        assertTrue(outer > 5800 && outer < 6800, "spread by area, not radius: " + outer);
    }

    @Test
    void capsSearchesServerWideAndPerPlayer() {
        Search search = new Search(null, null);
        UUID steve = UUID.randomUUID();
        assertEquals(Search.Start.STARTED, search.begin(steve, 2));
        assertEquals(Search.Start.ALREADY, search.begin(steve, 2));
        assertEquals(Search.Start.STARTED, search.begin(null, 2));
        assertEquals(Search.Start.BUSY, search.begin(UUID.randomUUID(), 2));
        search.end(steve);
        assertEquals(1, search.running());
        assertEquals(Search.Start.STARTED, search.begin(steve, 2));
    }

    @Test
    void boundsKeepInsideTheBorder() {
        Search.Bounds bounds = new Search.Bounds(-100, 100, -50, 50);
        assertTrue(bounds.contains(100, -50));
        assertTrue(!bounds.contains(101, 0));
        assertTrue(!bounds.contains(0, 51));
    }
}
