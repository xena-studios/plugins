package co.xenastudios.core.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.api.Location;
import co.xenastudios.core.api.PlayerRef;
import co.xenastudios.core.api.PlayerRefs;
import co.xenastudios.core.api.WorldRef;
import co.xenastudios.core.api.event.EventPriority;
import co.xenastudios.core.api.event.Subscription;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * core-api from Java, compiled with -Xlint:all -Werror at Java 8: java.util.UUID in and out,
 * CompletableFuture for what finishes later, lambdas for listeners, and getters and setters
 * for properties (DECISIONS.md, M0's rules for Java callers).
 */
class JavaApiTest {

    private final UUID notch = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void playersByJavaUuid() {
        PlayerRef ref = PlayerRefs.of(notch);
        assertEquals(notch, PlayerRefs.javaUuid(ref));
        assertEquals(ref, PlayerRef.parseOrNull(notch.toString()));
    }

    @Test
    void locationsWithOverloadedConstructors() {
        Location here = new Location(WorldRef.of("world"), 17.5, 64, -0.5);
        assertEquals(1, here.getChunkX());
        assertEquals(-1, here.getChunkZ());
        assertEquals(90f, new Location(WorldRef.of("world"), 0, 0, 0, 90f).getYaw());
    }

    @Test
    void pendingIsACompletableFuture() throws Exception {
        SampleApi api = new SampleApi();
        CompletableFuture<Long> balance = api.balance(PlayerRefs.of(notch));
        assertEquals(Long.valueOf(1000L), balance.thenApply(b -> b).get());
    }

    @Test
    void listenersAreLambdasAndPreEventsCancel() {
        SampleApi api = new SampleApi();
        List<String> heard = new ArrayList<>();
        api.events().subscribe(SampleApi.Withdraw.TYPE, event -> {
            if (event.getAmount() > 100) {
                event.setCancelled(true);
            }
        });
        Subscription monitor = api.events().subscribe(SampleApi.Withdraw.TYPE, EventPriority.MONITOR, event ->
            heard.add(event.getAmount() + (event.isCancelled() ? " cancelled" : " allowed")));
        api.events().subscribe(SampleApi.Withdraw.TYPE, EventPriority.HIGH, true, event -> heard.add("high"));

        assertTrue(api.withdraw(PlayerRefs.of(notch), 50));
        assertFalse(api.withdraw(PlayerRefs.of(notch), 500));
        monitor.cancel();
        assertTrue(api.withdraw(PlayerRefs.of(notch), 1));
        assertEquals(Arrays.asList("high", "50 allowed", "500 cancelled", "high"), heard);
    }
}
