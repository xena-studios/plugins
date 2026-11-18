package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The libraries xBackup downloads are pinned by hash in {@link Libraries}. The same versions
 * are test dependencies (from gradle/libs.versions.toml), so a version bump that forgets the
 * hash fails here, not on a server.
 */
class LibrariesTest {

    @Test
    void pinnedHashesMatchTheCatalogVersions() throws Exception {
        check(Libraries.JSCH, com.jcraft.jsch.JSch.class);
        check(Libraries.COMMONS_NET, org.apache.commons.net.ftp.FTPClient.class);
    }

    private static void check(Libraries.Library library, Class<?> type) throws Exception {
        Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(library.fileName(), jar.getFileName().toString(),
            "gradle/libs.versions.toml and Libraries disagree on the version");
        assertEquals(library.sha256(), Libraries.sha256(jar), "update the SHA-256 in Libraries for " + library.fileName());
    }
}
