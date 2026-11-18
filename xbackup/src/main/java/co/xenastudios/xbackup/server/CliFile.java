package co.xenastudios.xbackup.server;

import co.xenastudios.xbackup.remote.RemoteConfig;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * What the command-line restore needs from config.yml, which it can't read without a
 * server: where the repository is and how to reach each remote. Written whenever the config
 * loads, readable by the file's owner only, since it holds the remotes' passwords from
 * {@link Secrets}. Never backed up.
 */
public final class CliFile {

    public static final String NAME = co.xenastudios.xbackup.remote.RestoreSettings.NAME;

    private CliFile() {}

    static void write(Path home, Settings settings, Map<String, RemoteConfig> remotes, Logger logger) {
        Properties properties = new Properties();
        properties.setProperty("repository", settings.repository().toString());
        for (Map.Entry<String, RemoteConfig> entry : remotes.entrySet()) {
            String key = "remote." + entry.getKey() + ".";
            RemoteConfig remote = entry.getValue();
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
            StringWriter text = new StringWriter();
            properties.store(text, "For the command-line restore (java -jar xBackup.jar restore). Written from config.yml"
                + " and secrets.yml; edits here are overwritten. Never backed up.");
            co.xenastudios.xbackup.remote.OwnerOnlyFile.write(home.resolve(NAME), text.toString());
        } catch (IOException e) {
            logger.warning("Couldn't write " + NAME + " for the command-line restore: " + e.getMessage());
        }
    }
}
