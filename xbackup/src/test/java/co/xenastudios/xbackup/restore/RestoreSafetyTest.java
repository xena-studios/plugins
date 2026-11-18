package co.xenastudios.xbackup.restore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.store.BlobType;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import co.xenastudios.xbackup.store.Tree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A hostile repository or remote can't make a restore write outside its folder. */
class RestoreSafetyTest {

    @TempDir
    Path dir;

    private Repository repository;
    private Session session;

    private Repository open() throws IOException {
        repository = Repository.open(new LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true);
        session = repository.session(Session.Options.DEFAULTS);
        return repository;
    }

    private Tree.Node file(String name, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Hash blob = session.store(BlobType.DATA, bytes, true);
        return Tree.Node.file(name, 1_700_000_000_000L, bytes.length, -1, List.of(blob), Hash.sha256(bytes));
    }

    private Tree.Node folder(String name, Tree.Node... nodes) throws IOException {
        return Tree.Node.dir(name, 1_700_000_000_000L, -1, session.storeTree(new Tree(List.of(nodes))));
    }

    private Snapshot snapshot(Tree.Node... top) throws IOException {
        Hash tree = session.storeTree(new Tree(List.of(top)));
        Instant now = Instant.now();
        return session.commit(new Snapshot(null, now, now, tree, null, "test", 1, 1, 1, 0, 0, "test", List.of()));
    }

    private Restore.Written restore(Snapshot snapshot, Path into, boolean allowLinksOutside) throws IOException {
        List<Restore.Item> items = Restore.expand(repository, snapshot, List.of(""), Set.of());
        return Restore.write(repository, items, into, into, allowLinksOutside, Throttle.NONE, () -> false, step -> {});
    }

    @Test
    void aLinkPointingOutsideIsRefused() throws IOException {
        try (Repository opened = open()) {
            Snapshot up = snapshot(folder("plugins", Tree.Node.link("evil", 0, "../../outside")), file("a.txt", "a"));
            Path into = dir.resolve("server");
            Restore.UnsafeException refused = assertThrows(Restore.UnsafeException.class, () -> restore(up, into, false));
            assertTrue(refused.getMessage().contains("outside"), refused.getMessage());
            assertFalse(Files.exists(into.resolve("plugins/evil"), java.nio.file.LinkOption.NOFOLLOW_LINKS));

            Snapshot absolute = snapshot(Tree.Node.link("passwd", 0, dir.resolve("secret").toString()));
            assertThrows(Restore.UnsafeException.class, () -> restore(absolute, dir.resolve("server2"), false));

            // Links that stay inside come back, and the owner can allow the rest on purpose.
            Snapshot inside = snapshot(folder("plugins", file("real.yml", "x"), Tree.Node.link("alias.yml", 0, "real.yml")),
                Tree.Node.link("world", 0, "worlds/main"));
            assertDoesNotThrow(() -> restore(inside, dir.resolve("server3"), false));
            assertEquals("real.yml", Files.readSymbolicLink(dir.resolve("server3/plugins/alias.yml")).toString());
            assertDoesNotThrow(() -> restore(up, dir.resolve("server4"), true));
            assertTrue(Files.isSymbolicLink(dir.resolve("server4/plugins/evil")));
        }
    }

    @Test
    void nothingIsWrittenThroughALink() throws IOException {
        try (Repository opened = open()) {
            // On a disk that ignores case, the link "A" and the folder "a" are one name: writing
            // a/x would follow the link. It must be refused there, and both kept apart elsewhere.
            Files.createDirectories(dir.resolve("probe"));
            Files.writeString(dir.resolve("probe/Q"), "");
            boolean ignoresCase = Files.exists(dir.resolve("probe/q"));
            Snapshot clash = snapshot(Tree.Node.link("A", 0, "target"), folder("a", file("x", "through the link")),
                folder("target"));
            Path into = dir.resolve("server");
            if (ignoresCase) {
                Restore.UnsafeException refused = assertThrows(Restore.UnsafeException.class, () -> restore(clash, into, false));
                assertTrue(refused.getMessage().contains("case"), refused.getMessage());
            } else {
                restore(clash, into, false);
                assertTrue(Files.isRegularFile(into.resolve("a/x")));
            }
            assertFalse(Files.exists(into.resolve("target/x")), "a file was written through the link");
        }
    }

    @Test
    void namesThatArentPlainAreRefused() {
        assertThrows(Restore.UnsafeException.class, () -> Restore.checkName("..\\..\\evil", "plugins", true));
        assertThrows(Restore.UnsafeException.class, () -> Restore.checkName("C:evil", "", true));
        assertThrows(Restore.UnsafeException.class, () -> Restore.checkName("file:stream", "", true));
        assertThrows(Restore.UnsafeException.class, () -> Restore.checkName("bell\u0007", "", false));
        assertThrows(Restore.UnsafeException.class, () -> Restore.checkName("new\nline", "", false));
        // Fine on Linux and macOS, where these are ordinary characters in a name.
        assertDoesNotThrow(() -> Restore.checkName("minecraft:stone.yml", "", false));
        assertDoesNotThrow(() -> Restore.checkName("back\\slash", "", false));
        assertDoesNotThrow(() -> Restore.checkName("ünïcode name.yml", "", true));
        List<String> unused = new ArrayList<>();
        assertTrue(unused.isEmpty());
    }
}
