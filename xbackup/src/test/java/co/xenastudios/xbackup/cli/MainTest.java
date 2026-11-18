package co.xenastudios.xbackup.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.Fixtures;
import co.xenastudios.xbackup.backup.Backup;
import co.xenastudios.xbackup.backup.Databases;
import co.xenastudios.xbackup.restore.ServerCheck;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Throttle;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command-line restore, as an owner runs it with the server stopped (or not). */
class MainTest {

    @TempDir
    Path dir;

    private Path server;
    private byte[] level;

    @BeforeEach
    void backUp() throws Exception {
        server = dir.resolve("server");
        Fixtures.server(server, new Random(40));
        level = Files.readAllBytes(server.resolve("world/level.dat"));
        Path repo = server.resolve("plugins/xBackup/repository");
        try (Repository repository = Repository.open(new LocalBackend(repo), dir.resolve("temp"), true);
             AutoCloseable lock = repository.lock(true)) {
            Backup.run(repository, new Backup.Plan(server, List.of("world"), List.of("plugins", "server.properties"),
                List.of("**/session.lock"), Set.of(repo), Map.of(), "test", "test", dir.resolve("temp"), Throttle.NONE,
                Session.Options.DEFAULTS), Backup.Pause.NONE, Databases.sqlite(), () -> false, step -> {});
        }
    }

    private record Run(int code, String out) {}

    private Run run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Main main = new Main(new PrintStream(bytes, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")));
        String[] all = new String[args.length + 2];
        all[0] = "--server";
        all[1] = server.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        int code = main.run(all);
        return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aRestoreInPlacePutsTheBackupBack() throws Exception {
        Files.write(server.resolve("world/level.dat"), new byte[] {1, 2, 3});
        Run dry = run("restore", "latest", "world", "--dry-run");
        assertEquals(0, dry.code(), dry.out());
        assertTrue(dry.out().contains("Dry run"), dry.out());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(server.resolve("world/level.dat")));
        Run done = run("restore", "latest", "world", "--yes");
        assertEquals(0, done.code(), done.out());
        assertArrayEquals(level, Files.readAllBytes(server.resolve("world/level.dat")));
    }

    @Test
    void aRunningServerIsNeverRestoredUnder() throws Exception {
        Path marker = server.resolve("plugins/xBackup").resolve(ServerCheck.MARKER);
        try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = channel.lock()) {
            Files.write(server.resolve("world/level.dat"), new byte[] {9});
            Run refused = run("restore", "latest", "world", "--yes");
            assertEquals(1, refused.code(), refused.out());
            assertTrue(refused.out().contains("The server is running"), refused.out());
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(server.resolve("world/level.dat")), "nothing changed");
            // Into another, empty folder is fine while it runs.
            Run elsewhere = run("restore", "latest", "world", "--to", dir.resolve("copy").toString(), "--yes");
            assertEquals(0, elsewhere.code(), elsewhere.out());
            assertArrayEquals(level, Files.readAllBytes(dir.resolve("copy/world/level.dat")));
        }
    }

    @Test
    void toMustBeEmpty() throws Exception {
        Files.createDirectories(dir.resolve("full"));
        Files.writeString(dir.resolve("full/keep.txt"), "mine");
        Run refused = run("restore", "latest", "--to", dir.resolve("full").toString(), "--yes");
        assertEquals(1, refused.code(), refused.out());
        assertTrue(refused.out().contains("isn't empty"), refused.out());
        Run live = run("restore", "latest", "--to", server.toString(), "--yes");
        assertEquals(1, live.code(), live.out());
        assertEquals("mine", Files.readString(dir.resolve("full/keep.txt")));
    }
}
