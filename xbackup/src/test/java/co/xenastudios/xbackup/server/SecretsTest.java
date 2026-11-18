package co.xenastudios.xbackup.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Remote passwords live in an owner-only file, never in config.yml, which is backed up and shared. */
class SecretsTest {

    @TempDir
    Path home;

    private static final Logger LOGGER = Logger.getLogger("test");

    @Test
    void passwordsInConfigMoveToTheSecretsFile() throws Exception {
        Path config = home.resolve("config.yml");
        Files.writeString(config, """
            # My remotes.
            repository: plugins/xBackup/repository
            remotes:
              offsite:
                type: sftp
                username: backup
                password: "hunter2"
                host: example.com
              gdrive:
                type: drive
                client-id: abc
                client-secret: s3cr3t
              plain:
                type: local
                path: /mnt/backups
            """);
        assertEquals(List.of("offsite", "gdrive"), Secrets.migrate(home, config, LOGGER));

        String after = Files.readString(config);
        assertFalse(after.contains("hunter2") || after.contains("s3cr3t"), after);
        assertTrue(after.contains("username: backup") && after.contains("client-id: abc"), "the rest is kept: " + after);
        Path secrets = home.resolve(Secrets.NAME);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets)));

        Map<String, Secrets.Entry> read = Secrets.read(home, config, LOGGER);
        assertEquals("hunter2", read.get("offsite").password());
        assertEquals("s3cr3t", read.get("gdrive").clientSecret());
        // Nothing left to move the second time.
        assertEquals(List.of(), Secrets.migrate(home, config, LOGGER));
        assertEquals("hunter2", Secrets.read(home, config, LOGGER).get("offsite").password());
    }

    @Test
    void aPasswordTypedIntoConfigLaterReplacesTheOldOne() throws Exception {
        Path config = home.resolve("config.yml");
        Files.writeString(home.resolve(Secrets.NAME), "offsite:\n  password: old\nother:\n  password: kept\n");
        Files.setPosixFilePermissions(home.resolve(Secrets.NAME), PosixFilePermissions.fromString("rw-r--r--"));
        Files.writeString(config, "remotes:\n  offsite:\n    type: s3\n    password: new\n");
        Secrets.migrate(home, config, LOGGER);
        Map<String, Secrets.Entry> read = Secrets.read(home, config, LOGGER);
        assertEquals("new", read.get("offsite").password());
        assertEquals("kept", read.get("other").password());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(Secrets.NAME))));
    }

    @Test
    void noRemotesMeansAnEmptyOwnerOnlyFile() throws Exception {
        Path config = home.resolve("config.yml");
        Files.writeString(config, "remotes: {}\n");
        assertEquals(List.of(), Secrets.migrate(home, config, LOGGER));
        assertTrue(Secrets.read(home, config, LOGGER).isEmpty());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(Secrets.NAME))));
        assertTrue(Files.readString(home.resolve(Secrets.NAME)).contains("never backs it up"));
    }
}
