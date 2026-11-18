package co.xenastudios.xbackup.restore;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command-line restore sees a running server wherever its worlds are. */
class ServerCheckTest {

    @TempDir
    Path dir;

    // Locks the file as a running server would, from another process. A second JVM is costly,
    // so the same JVM's lock stands in: ServerCheck treats "already locked here" the same.
    private static AutoCloseable lock(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.lock();
        return () -> {
            lock.release();
            channel.close();
        };
    }

    @Test
    void aWorldInACustomContainerIsSeen() throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server.resolve("worlds/survival"));
        Files.writeString(server.resolve("bukkit.yml"), "settings:\n  allow-end: true\n  world-container: worlds\n");
        Files.writeString(server.resolve("worlds/survival/level.dat"), "");
        assertNull(ServerCheck.running(server, List.of("worlds/survival")));
        try (AutoCloseable held = lock(server.resolve("worlds/survival/session.lock"))) {
            String running = ServerCheck.running(server, List.of("plugins"));
            assertNotNull(running);
            assertTrue(running.contains("session.lock"), running);
        }
    }

    @Test
    void aContainerOutsideTheServerFolderIsSeen() throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server);
        Path elsewhere = dir.resolve("disk2/worlds");
        Files.writeString(server.resolve("bukkit.yml"), "settings:\n  world-container: '" + elsewhere + "'\n");
        try (AutoCloseable held = lock(elsewhere.resolve("world/session.lock"))) {
            assertNotNull(ServerCheck.running(server, List.of("")));
        }
    }

    @Test
    void xBackupsOwnMarkerIsSeen() throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server.resolve("plugins/xBackup"));
        try (AutoCloseable held = lock(server.resolve("plugins/xBackup").resolve(ServerCheck.MARKER))) {
            String running = ServerCheck.running(server, List.of("world"));
            assertNotNull(running);
            assertTrue(running.contains(ServerCheck.MARKER), running);
        }
        assertNull(ServerCheck.running(server, List.of("world")), "a stopped server leaves the file unlocked");
    }

    @Test
    void toMustBeNewOrEmpty() throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server);
        assertNull(ServerCheck.notEmpty(dir.resolve("new"), server));
        Files.createDirectories(dir.resolve("empty"));
        assertNull(ServerCheck.notEmpty(dir.resolve("empty"), server));
        Files.createDirectories(dir.resolve("full"));
        Files.writeString(dir.resolve("full/x"), "");
        assertNotNull(ServerCheck.notEmpty(dir.resolve("full"), server));
        assertNotNull(ServerCheck.notEmpty(server, server), "the live server folder");
        assertNotNull(ServerCheck.notEmpty(dir, server), "a folder holding the server");
    }
}
