package co.xenastudios.xlimbo.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xlimbo.api.event.MainServerStatusEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** MainServerStatusEvent fires for a change in what server-check saw, never for the first answer. */
class StatusEventTest {

    ServerMock server;
    List<MainServerStatusEvent> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            void on(MainServerStatusEvent event) {
                seen.add(event);
            }
        }, MockBukkit.createMockPlugin("Other"));
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    // The ping runs off the main thread, as the event says.
    void answer(Boolean before, boolean now) {
        CompletableFuture.runAsync(() -> Returns.changed(before, now)).join();
    }

    @Test
    void onlyChangesFire() {
        answer(null, true);
        answer(null, false);
        answer(true, true);
        answer(false, false);
        assertEquals(0, seen.size(), "a first answer, or the same answer, isn't a change");

        answer(true, false);
        answer(false, true);
        assertEquals(2, seen.size());
        assertTrue(!seen.get(0).isUp() && seen.get(1).isUp());
        assertTrue(seen.get(0).isAsynchronous());
    }
}
