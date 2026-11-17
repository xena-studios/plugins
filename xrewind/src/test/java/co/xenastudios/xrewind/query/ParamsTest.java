package co.xenastudios.xrewind.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ParamsTest {

    @BeforeEach
    void start() {
        MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void readsCoreProtectsParameters() {
        Query query = Params.parse(List.of("u:Notch,#TNT", "t:2w3d", "r:10", "a:-block", "i:stone,OAK_LOG", "e:dirt,Herobrine", "#preview"));
        assertEquals(List.of("notch", "#tnt"), query.users());
        assertEquals(17L * 86_400, query.seconds());
        assertEquals(10, query.radius());
        assertEquals(Set.of(Query.Action.BREAK), query.actions());
        assertEquals(List.of("stone", "oak_log"), query.blocks());
        assertEquals(List.of("dirt"), query.excludedBlocks());
        assertEquals(List.of("herobrine"), query.excludedUsers());
        assertTrue(query.preview());
    }

    @Test
    void radiusCanBeAWorldOrEverywhere() {
        assertEquals(Query.WORLD, Params.parse(List.of("r:#world")).radius());
        assertEquals(Query.GLOBAL, Params.parse(List.of("r:#global")).radius());
        assertEquals(Query.UNSET, Params.parse(List.of()).radius());
    }

    @Test
    void mistakesSayWhatsWrong() {
        for (String bad : new String[] {"x:1", "t:soon", "r:far", "a:dance", "i:notablock", "#loud", "stone", "u:"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Params.parse(List.of(bad)), bad);
            assertTrue(!e.getMessage().isBlank());
        }
    }
}
