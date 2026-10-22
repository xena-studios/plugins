package co.xenastudios.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void ordersReleases() {
        assertTrue(Versions.compare("2.0.1", "2.0.0") > 0);
        assertTrue(Versions.compare("2.10.0", "2.9.0") > 0, "numerically, not as text");
        assertTrue(Versions.compare("3", "2.9.9") > 0);
        assertEquals(0, Versions.compare("2.0", "2.0.0"));
    }

    @Test
    void aPrereleaseComesBeforeItsRelease() {
        assertTrue(Versions.compare("2.0.0-beta.1", "2.0.0") < 0);
        assertTrue(Versions.compare("2.0.0-beta.2", "2.0.0-beta.1") > 0);
        assertTrue(Versions.compare("2.0.0-beta.10", "2.0.0-beta.9") > 0);
        assertTrue(Versions.compare("2.0.1-beta.1", "2.0.0") > 0);
        assertTrue(Versions.isPrerelease("2.0.0-rc.1"));
    }
}
