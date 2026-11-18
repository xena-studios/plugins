package co.xenastudios.xteleport.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Requests: several pending, one per sender, and running out only when looked at. */
class RequestsTest {

    static final long EXPIRE = 60_000;
    final AtomicLong now = new AtomicLong(1_000_000);
    final Requests requests = new Requests(now::get);
    final UUID alex = UUID.randomUUID();
    final UUID steve = UUID.randomUUID();
    final UUID sam = UUID.randomUUID();

    Requests.Request from(UUID who, String name) {
        return new Requests.Request(who, name, false, now.get());
    }

    @Test
    void severalPendingOnePerSender() {
        requests.add(alex, from(steve, "Steve"), EXPIRE);
        now.addAndGet(1000);
        requests.add(alex, from(sam, "Sam"), EXPIRE);
        now.addAndGet(1000);
        requests.add(alex, new Requests.Request(steve, "Steve", true, now.get()), EXPIRE);
        List<Requests.Request> pending = requests.pending(alex, EXPIRE);
        assertEquals(2, pending.size());
        assertEquals("Sam", pending.get(0).fromName());
        assertTrue(pending.get(1).here(), "a second request from Steve replaces his first");
    }

    @Test
    void takesTheNewestOrTheNamedOne() {
        requests.add(alex, from(steve, "Steve"), EXPIRE);
        now.addAndGet(1000);
        requests.add(alex, from(sam, "Sam"), EXPIRE);
        assertEquals(sam, requests.take(alex, null, EXPIRE).orElseThrow().from());
        assertTrue(requests.take(alex, sam, EXPIRE).isEmpty());
        assertEquals(steve, requests.take(alex, steve, EXPIRE).orElseThrow().from());
        assertEquals(0, requests.lists(), "an empty list is dropped");
    }

    @Test
    void runOutWhenRead() {
        requests.add(alex, from(steve, "Steve"), EXPIRE);
        now.addAndGet(EXPIRE + 1);
        assertEquals(1, requests.lists(), "nothing runs on a timer");
        assertTrue(requests.take(alex, null, EXPIRE).isEmpty());
        assertEquals(0, requests.lists());
    }

    @Test
    void addingDropsOnesThatRanOut() {
        requests.add(alex, from(steve, "Steve"), EXPIRE);
        now.addAndGet(EXPIRE + 1);
        requests.add(alex, from(sam, "Sam"), EXPIRE);
        assertEquals(List.of(sam), requests.pending(alex, EXPIRE).stream().map(Requests.Request::from).toList());
    }

    @Test
    void cancelTakesBackWhatWasSent() {
        requests.add(alex, from(steve, "Steve"), EXPIRE);
        requests.add(sam, from(steve, "Steve"), EXPIRE);
        requests.add(sam, from(alex, "Alex"), EXPIRE);
        assertEquals(List.of(alex), requests.cancel(steve, alex, EXPIRE));
        assertEquals(List.of(sam), requests.cancel(steve, null, EXPIRE));
        assertEquals(1, requests.pending(sam, EXPIRE).size());
        assertTrue(requests.cancel(steve, null, EXPIRE).isEmpty());
    }

    @Test
    void forgetsPlayersWhoLeave() {
        requests.add(alex, from(steve, "Steve"), EXPIRE);
        requests.forget(alex);
        assertEquals(0, requests.lists());
    }
}
