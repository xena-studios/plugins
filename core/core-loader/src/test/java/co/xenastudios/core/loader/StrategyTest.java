package co.xenastudios.core.loader;

import static co.xenastudios.core.loader.Strategy.ISOLATED;
import static co.xenastudios.core.loader.Strategy.LOOKUP;
import static co.xenastudios.core.loader.Strategy.REFLECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Which way the jars are added, as the M0 spike found works on each Java. */
class StrategyTest {

    @Test
    void java8ReflectsFirst() {
        assertEquals(List.of(REFLECT, LOOKUP, ISOLATED), Strategy.order(8, true, null));
    }

    @Test
    void newerJavaUsesTheTrustedLookupFirst() {
        // Reflection warns on 9-15 and is refused on 16+ without --add-opens, so it comes second.
        for (int java : new int[] {9, 11, 16, 17, 21, 25}) {
            assertEquals(List.of(LOOKUP, REFLECT, ISOLATED), Strategy.order(java, true, null), "Java " + java);
        }
    }

    @Test
    void aLoaderThatIsNoUrlClassLoaderCanOnlyBeIsolated() {
        assertEquals(List.of(ISOLATED), Strategy.order(8, false, null));
        assertEquals(List.of(ISOLATED), Strategy.order(21, false, null));
    }

    @Test
    void anOwnersChoiceIsTheOnlyOneTried() {
        assertEquals(List.of(REFLECT), Strategy.order(21, true, REFLECT));
        assertEquals(List.of(ISOLATED), Strategy.order(8, true, ISOLATED));
    }

    @Test
    void parsesTheSetting() {
        assertNull(Strategy.parse(null));
        assertNull(Strategy.parse(" "));
        assertNull(Strategy.parse("Auto"));
        assertEquals(LOOKUP, Strategy.parse(" lookup "));
        assertEquals(ISOLATED, Strategy.parse("ISOLATED"));
        Fixtures.assertContains(assertThrows(IllegalArgumentException.class, () -> Strategy.parse("global")).getMessage(),
            "\"global\" isn't auto, reflect, lookup or isolated");
    }
}
