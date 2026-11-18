package co.xenastudios.xbackup.server;

import co.xenastudios.xbackup.config.XBConfig;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * What the command-line restore needs from config.yml, which it can't read without a
 * server: where the repository is and how to reach each remote. Written whenever the config
 * loads, readable by the file's owner only, since it holds the remotes' passwords.
 */
public final class CliFile {

    public static final String NAME = co.xenastudios.xbackup.remote.RestoreSettings.NAME;

    private CliFile() {}

    static void write(Path home, Settings settings, Map<String, XBConfig.Remote> remotes, Logger logger) {
        Properties properties = new Properties();
        properties.setProperty("repository", settings.repository().toString());
        for (Map.Entry<String, XBConfig.Remote> entry : remotes.entrySet()) {
            String key = "remote." + entry.getKey() + ".";
            XBConfig.Remote remote = entry.getValue();
            properties.setProperty(key + "type", remote.type());
            properties.setProperty(key + "enabled", Boolean.toString(remote.enabled()));
            properties.setProperty(key + "path", remote.path());
            properties.setProperty(key + "url", remote.url());
            properties.setProperty(key + "region", remote.region());
            properties.setProperty(key + "bucket", remote.bucket());
            properties.setProperty(key + "path-style", Boolean.toString(remote.pathStyle()));
            properties.setProperty(key + "username", remote.username());
            properties.setProperty(key + "password", remote.password());
            properties.setProperty(key + "host", remote.host());
            properties.setProperty(key + "port", Integer.toString(remote.port()));
            properties.setProperty(key + "private-key", remote.privateKey());
            properties.setProperty(key + "host-key", remote.hostKey());
            properties.setProperty(key + "security", remote.security());
            properties.setProperty(key + "client-id", remote.clientId());
            properties.setProperty(key + "client-secret", remote.clientSecret());
        }
        try {
            Files.createDirectories(home);
            StringWriter text = new StringWriter();
            properties.store(text, "For the command-line restore (java -jar xBackup.jar restore). Written from config.yml;"
                + " edits here are overwritten.");
            Path temp = home.resolve(NAME + ".part");
            Files.writeString(temp, text.toString(), StandardCharsets.UTF_8);
            ownerOnly(temp);
            Files.move(temp, home.resolve(NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (!remotes.isEmpty()) {
                ownerOnly(home.resolve("config.yml"));
            }
        } catch (IOException e) {
            logger.warning("Couldn't write " + NAME + " for the command-line restore: " + e.getMessage());
        }
    }

    /** Makes a file readable by its owner only, where the system has permissions. */
    public static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the folder's permissions apply.
        }
    }

}
