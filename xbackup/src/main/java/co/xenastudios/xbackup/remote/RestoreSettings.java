package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.config.XBConfig;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The settings the command-line restore reads, since it can't read config.yml without a
 * server: the plugin writes them to {@value #NAME} whenever its config loads.
 *
 * @param repository the repository folder, or null if not recorded
 */
public record RestoreSettings(Path repository, Map<String, XBConfig.Remote> remotes) {

    public static final String NAME = "restore-settings.properties";

    /** Reads the file; null if it isn't there. */
    public static RestoreSettings read(Path home) throws IOException {
        Path file = home.resolve(NAME);
        if (!Files.exists(file)) {
            return null;
        }
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        Map<String, XBConfig.Remote> remotes = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("remote.") && key.endsWith(".type")) {
                String name = key.substring(7, key.length() - 5);
                String p = "remote." + name + ".";
                remotes.put(name, new XBConfig.Remote(properties.getProperty(p + "type", ""),
                    Boolean.parseBoolean(properties.getProperty(p + "enabled", "true")), properties.getProperty(p + "path", ""),
                    properties.getProperty(p + "url", ""), properties.getProperty(p + "region", ""),
                    properties.getProperty(p + "bucket", ""), Boolean.parseBoolean(properties.getProperty(p + "path-style")),
                    properties.getProperty(p + "username", ""), properties.getProperty(p + "password", ""),
                    properties.getProperty(p + "host", ""), Integer.parseInt(properties.getProperty(p + "port", "0")),
                    properties.getProperty(p + "private-key", ""), properties.getProperty(p + "host-key", ""),
                    properties.getProperty(p + "security", "explicit"), properties.getProperty(p + "client-id", ""),
                    properties.getProperty(p + "client-secret", "")));
            }
        }
        String repository = properties.getProperty("repository");
        return new RestoreSettings(repository == null ? null : Path.of(repository), remotes);
    }
}
