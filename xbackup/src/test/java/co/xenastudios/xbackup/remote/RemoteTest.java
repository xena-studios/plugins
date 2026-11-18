package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.xenastudios.xbackup.Fixtures;
import co.xenastudios.xbackup.backup.Backup;
import co.xenastudios.xbackup.backup.Databases;
import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every network remote against a real server: MinIO for S3, Apache for WebDAV, OpenSSH for
 * SFTP and vsftpd for FTP, run in Docker. Skipped unless XB_REMOTES is set; the servers to
 * start are in the docs' contributing notes (xbackup/README.md).
 */
class RemoteTest {

    @TempDir
    Path dir;

    static Stream<String> remotes() {
        return Stream.of("s3", "webdav", "sftp", "ftp");
    }

    private Backend open(String type) throws Exception {
        String folder = "xb-" + UUID.randomUUID().toString().substring(0, 8);
        Remotes.Context context = new Remotes.Context(dir.resolve("home"), Throttle.NONE, Logger.getLogger("test"), t -> null);
        Files.createDirectories(context.home());
        XBConfig.Remote settings = switch (type) {
            case "s3" -> new XBConfig.Remote("s3", true, folder, "http://127.0.0.1:19000", "us-east-1", "xbackup-test", true,
                "xbackup", "xbackupsecret", "", 0, "", "", "explicit", "", "");
            case "webdav" -> new XBConfig.Remote("webdav", true, folder, "http://127.0.0.1:18080/", "", "", false,
                "xbackup", "pass", "", 0, "", "", "explicit", "", "");
            case "sftp" -> new XBConfig.Remote("sftp", true, "upload/" + folder, "", "", "", false, "xbackup", "pass",
                "127.0.0.1", 12222, "", "", "explicit", "", "");
            default -> new XBConfig.Remote("ftp", true, folder, "", "", "", false, "xbackup", "pass", "127.0.0.1", 12121,
                "", "", "none", "", "");
        };
        Backend backend = Remotes.open(type, settings, context);
        if (backend instanceof S3Backend s3) {
            s3.createBucket();
        }
        return backend;
    }

    @ParameterizedTest
    @MethodSource("remotes")
    void putGetListReadDelete(String type) throws Exception {
        assumeTrue(System.getenv("XB_REMOTES") != null, "XB_REMOTES isn't set");
        try (Backend backend = open(type)) {
            byte[] data = new byte[300_000];
            new Random(1).nextBytes(data);
            backend.put("data/ab/one", data);
            Path file = dir.resolve("file");
            Files.write(file, data);
            backend.put("index/two", file);
            backend.put("index/two", file);
            List<Backend.FileInfo> listed = backend.list("");
            assertEquals(Set.of(new Backend.FileInfo("data/ab/one", 300_000), new Backend.FileInfo("index/two", 300_000)),
                Set.copyOf(listed));
            assertEquals(1, backend.list("data/").size());
            try (InputStream in = backend.get("data/ab/one")) {
                assertArrayEquals(data, in.readAllBytes());
            }
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 1000, 1500), backend.read("index/two", 1000, 500));
            assertThrows(NoSuchFileException.class, () -> backend.get("data/ab/missing").close());
            backend.delete("data/ab/one");
            backend.delete("data/ab/one");
            assertEquals(1, backend.list("").size());
            backend.delete("index/two");
        }
    }

    @ParameterizedTest
    @MethodSource("remotes")
    void copyThenRestoreStraightFromTheRemote(String type) throws Exception {
        assumeTrue(System.getenv("XB_REMOTES") != null, "XB_REMOTES isn't set");
        Path server = dir.resolve("server");
        Fixtures.server(server, new Random(2));
        Snapshot snapshot;
        try (Repository repository = Repository.open(new LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true)) {
            snapshot = Backup.run(repository, new Backup.Plan(server, List.of("world", "world_nether"),
                List.of("plugins", "server.properties"), List.of("**/session.lock"), Set.of(), Map.of(), "remote", "test",
                dir.resolve("temp"), Throttle.NONE, Session.Options.DEFAULTS), Backup.Pause.NONE, Databases.sqlite(),
                () -> false, step -> {}).snapshot();
            try (Backend remote = open(type)) {
                Mirror.Result first = Mirror.push(repository.backend(), remote, () -> false, step -> {});
                assertTrue(first.filesSent() > 3, first.toString());
                // Nothing new: nothing sent.
                assertEquals(0, Mirror.push(repository.backend(), remote, () -> false, step -> {}).filesSent());

                Repository cached = Fetch.metadata(remote, dir.resolve("cache"), step -> {});
                List<Restore.Item> items = Restore.expand(cached, snapshot, List.of(""), Set.of());
                Fetch.data(remote, cached, items, step -> {});
                Restore.write(cached, items, dir.resolve("restored"), Throttle.NONE, () -> false, step -> {});
                cached.close();
                for (Backend.FileInfo file : remote.list("")) {
                    remote.delete(file.name());
                }
            }
        }
        Map<String, byte[]> expected = Fixtures.contents(server, path -> !path.endsWith("session.lock")
            && !path.startsWith("logs/") && !path.equals("bukkit.yml"));
        Map<String, byte[]> actual = Fixtures.contents(dir.resolve("restored"), path -> true);
        assertEquals(expected.keySet(), actual.keySet());
        for (String path : expected.keySet()) {
            assertArrayEquals(expected.get(path), actual.get(path), path);
        }
    }
}
