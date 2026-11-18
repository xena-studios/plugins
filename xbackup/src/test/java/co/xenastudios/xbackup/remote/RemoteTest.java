package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.xenastudios.xbackup.Fixtures;
import co.xenastudios.xbackup.backup.Backup;
import co.xenastudios.xbackup.backup.Databases;
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
 * Every network remote against a real server: MinIO for S3, Apache for WebDAV (and rclone for
 * WebDAV served below the root, as Nextcloud does), OpenSSH for SFTP, and vsftpd for FTP and
 * for FTPS with a self-signed certificate, run in Docker. Skipped unless XB_REMOTES is set;
 * the servers to start are in xbackup/README.md. XB_FTPS_PIN is the FTPS certificate's
 * SHA-256 fingerprint.
 */
class RemoteTest {

    @TempDir
    Path dir;

    static Stream<String> remotes() {
        return Stream.of("s3", "webdav", "webdav-below-root", "sftp", "ftp", "ftps");
    }

    private Backend open(String type) throws Exception {
        String folder = "xb-" + UUID.randomUUID().toString().substring(0, 8);
        Remotes.Context context = new Remotes.Context(dir.resolve("home"), Throttle.NONE, Logger.getLogger("test"), t -> null);
        Files.createDirectories(context.home());
        RemoteConfig settings = switch (type) {
            case "s3" -> new RemoteConfig("s3", true, folder, "http://127.0.0.1:19000", "us-east-1", "xbackup-test", true,
                "xbackup", "xbackupsecret", "", 0, "", "", "explicit", "", "");
            case "webdav" -> new RemoteConfig("webdav", true, folder, "http://127.0.0.1:18080/", "", "", false,
                "xbackup", "pass", "", 0, "", "", "explicit", "", "");
            case "sftp" -> new RemoteConfig("sftp", true, "upload/" + folder, "", "", "", false, "xbackup", "pass",
                "127.0.0.1", 12222, "", "", "explicit", "", "");
            case "webdav-below-root" -> new RemoteConfig("webdav", true, folder,
                "http://127.0.0.1:18081/remote.php/dav/files/xbackup", "", "", false, "xbackup", "pass", "", 0, "", "",
                "explicit", "", "");
            case "ftps" -> new RemoteConfig("ftp", true, folder, "", "", "", false, "xbackup", "pass", "127.0.0.1", 12990,
                "", System.getenv().getOrDefault("XB_FTPS_PIN", ""), "explicit", "", "");
            default -> new RemoteConfig("ftp", true, folder, "", "", "", false, "xbackup", "pass", "127.0.0.1", 12121,
                "", "", "none", "", "");
        };
        Backend backend = Remotes.open(type.startsWith("webdav") ? "webdav" : type.startsWith("ftp") ? "ftp" : type,
            settings, context);
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
            // A pin is an empty file.
            backend.put("keep/" + "ab".repeat(32), new byte[0]);
            List<Backend.FileInfo> listed = backend.list("");
            assertEquals(Set.of(new Backend.FileInfo("data/ab/one", 300_000), new Backend.FileInfo("index/two", 300_000),
                new Backend.FileInfo("keep/" + "ab".repeat(32), 0)), Set.copyOf(listed));
            backend.delete("keep/" + "ab".repeat(32));
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
                Mirror.Result first = Mirror.push(repository, remote, () -> false, step -> {});
                assertTrue(first.filesSent() > 3, first.toString());
                // Nothing new: nothing sent.
                assertEquals(0, Mirror.push(repository, remote, () -> false, step -> {}).filesSent());

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

    @org.junit.jupiter.api.Test
    void ftpsRefusesACertificateJavaDoesntTrust() throws Exception {
        assumeTrue(System.getenv("XB_REMOTES") != null, "XB_REMOTES isn't set");
        Remotes.Context context = new Remotes.Context(dir.resolve("home"), Throttle.NONE, Logger.getLogger("test"), t -> null);
        Files.createDirectories(context.home());
        RemoteConfig unpinned = new RemoteConfig("ftp", true, "xb", "", "", "", false, "xbackup", "pass", "127.0.0.1", 12990,
            "", "", "explicit", "", "");
        try (Backend backend = Remotes.open("ftps", unpinned, context)) {
            java.io.IOException refused = assertThrows(java.io.IOException.class, () -> backend.list(""));
            assertTrue(String.valueOf(refused.getMessage()).contains("couldn't connect"), refused.getMessage());
            Throwable cause = refused;
            StringBuilder messages = new StringBuilder();
            while (cause != null) {
                messages.append(cause.getMessage()).append(" | ");
                cause = cause.getCause();
            }
            assertTrue(messages.toString().contains("SHA256:"), "says the fingerprint to pin: " + messages);
        }
        String pin = System.getenv("XB_FTPS_PIN");
        assumeTrue(pin != null, "XB_FTPS_PIN isn't set");
        RemoteConfig wrong = new RemoteConfig("ftp", true, "xb", "", "", "", false, "xbackup", "pass", "127.0.0.1", 12990,
            "", "SHA256:" + "A".repeat(43), "explicit", "", "");
        try (Backend backend = Remotes.open("ftps", wrong, context)) {
            assertThrows(java.io.IOException.class, () -> backend.list(""));
        }
    }
}
