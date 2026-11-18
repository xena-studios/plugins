package co.xenastudios.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.player.Profiles.Profile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ProfilesTest {

    final Profile steve = new Profile(UUID.randomUUID(), "Steve");
    final Profile alex = new Profile(UUID.randomUUID(), "Alex");
    final Profile notch = new Profile(UUID.randomUUID(), "Notch");
    final List<Runnable> queued = new ArrayList<>();
    final List<String> lookedUp = new ArrayList<>();
    long now = 1_000_000;

    /** Steve is online, Alex has joined before, Notch is only known to Mojang. */
    final Profiles.Source server = new Profiles.Source() {
        final Map<String, Profile> mojang = new HashMap<>(Map.of("notch", notch, "alex", alex));

        @Override
        public Profile online(String name) {
            return name.equalsIgnoreCase("steve") ? steve : null;
        }

        @Override
        public Profile online(UUID id) {
            return id.equals(steve.id()) ? steve : null;
        }

        @Override
        public Profile serverCached(String name) {
            return name.equalsIgnoreCase("alex") ? alex : null;
        }

        @Override
        public Profile lookUp(String name) {
            lookedUp.add(name);
            return mojang.get(name.toLowerCase());
        }

        @Override
        public Profile lookUp(UUID id) {
            lookedUp.add(id.toString());
            return mojang.values().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        }
    };

    final Profiles profiles = new Profiles(server, queued::add, () -> now);

    private void runQueued() {
        List<Runnable> work = new ArrayList<>(queued);
        queued.clear();
        work.forEach(Runnable::run);
    }

    @Test
    void whatsInMemoryIsAnsweredAtOnce() {
        assertEquals(Optional.of(steve), profiles.byName("STEVE").join());
        assertEquals(Optional.of(alex), profiles.byName("alex").join());
        assertEquals(Optional.of(steve), profiles.byId(steve.id()).join());
        assertEquals(Optional.of(alex), profiles.byId(alex.id()).join(), "remembered from the name lookup");
        assertTrue(queued.isEmpty(), "nothing went off-thread");
    }

    @Test
    void othersAreAskedOffThreadOnceAndRemembered() {
        CompletableFuture<Optional<Profile>> first = profiles.byName("Notch");
        CompletableFuture<Optional<Profile>> second = profiles.byName("notch");
        assertFalse(first.isDone(), "not on the calling thread");
        assertSame(first, second, "one lookup for both");
        runQueued();
        assertEquals(Optional.of(notch), first.join());
        assertEquals(List.of("Notch"), lookedUp);

        assertEquals(Optional.of(notch), profiles.byName("NOTCH").join(), "now from memory");
        assertEquals(Optional.of(notch), profiles.byId(notch.id()).join());
        assertEquals(1, lookedUp.size());
    }

    @Test
    void anUnknownNameIsntAskedAboutAgainForAWhile() {
        CompletableFuture<Optional<Profile>> typo = profiles.byName("Stevve");
        runQueued();
        assertEquals(Optional.empty(), typo.join());
        assertEquals(Optional.empty(), profiles.byName("stevve").join());
        assertEquals(1, lookedUp.size());

        now += Profiles.MISS_MS;
        profiles.byName("Stevve");
        runQueued();
        assertEquals(2, lookedUp.size(), "asked again after ten minutes");

        assertEquals(Optional.empty(), profiles.byName("not a name!").join());
        assertEquals(Optional.empty(), profiles.byName("x".repeat(17)).join());
        assertEquals(2, lookedUp.size(), "invalid names are never looked up");
    }

    @Test
    void aRenameReplacesTheOldName() {
        UUID id = UUID.randomUUID();
        profiles.remember(id, "OldName");
        assertEquals(Optional.of(new Profile(id, "OldName")), profiles.cached("oldname"));
        profiles.remember(id, "NewName");
        assertEquals(Optional.of(new Profile(id, "NewName")), profiles.cached(id));
        assertEquals(Optional.empty(), profiles.cached("oldname"));
    }

    @Test
    void aFailedLookupFailsTheFuture() {
        Profiles broken = new Profiles(new Profiles.Source() {
            @Override
            public Profile online(String name) {
                return null;
            }

            @Override
            public Profile online(UUID id) {
                return null;
            }

            @Override
            public Profile serverCached(String name) {
                return null;
            }

            @Override
            public Profile lookUp(String name) {
                throw new IllegalStateException("Mojang is down");
            }

            @Override
            public Profile lookUp(UUID id) {
                return null;
            }
        }, Runnable::run, () -> now);
        assertTrue(broken.byName("Steve").isCompletedExceptionally());
        assertTrue(broken.byName("Steve").isCompletedExceptionally(), "and isn't stuck as one in progress");
    }
}
