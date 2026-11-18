package co.xenastudios.xteleport.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HomeLimitsTest {

    @Test
    void readsNumberedPermissionsOnly() {
        assertEquals(5, HomeLimits.number("5"));
        assertEquals(0, HomeLimits.number("others"));
        assertEquals(0, HomeLimits.number("unlimited"));
        assertEquals(0, HomeLimits.number(""));
        assertEquals(0, HomeLimits.number("99999999"));
    }

    @Test
    void namesAreShort() {
        assertTrue(Homes.validName("base_2-north"));
        assertFalse(Homes.validName("my base"));
        assertFalse(Homes.validName("x".repeat(33)));
        assertFalse(Homes.validName(""));
    }
}
