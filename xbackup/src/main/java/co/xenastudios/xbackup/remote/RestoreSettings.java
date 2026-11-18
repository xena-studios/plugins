package co.xenastudios.xbackup.remote;

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
 * server: the plugin writes them to {@value #NAME} whenever its config loads. The remotes'
 * passwords are read from {@value SecretFile#SECRETS} where they are.
 *
 * @param repository the repository folder, or null if not recorded
 * @param secretsProblem why {@value SecretFile#SECRETS} couldn't be read, or null
 */
public record RestoreSettings(Path repository, Map<String, RemoteConfig> remotes, String secretsProblem) {

    public static final String NAME = "restore-settings.properties";

    /** Reads the file; null if it isn't there. */
    public static RestoreSettings read(Path home) throws IOException {
        Path file = home.resolve(NAME);
        if (!Files.exists(file)) {
            return null;
        }
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        Map<String, Map<String, String>> secrets;
        String secretsProblem = null;
        try {
            secrets = SecretFile.read(home.resolve(SecretFile.SECRETS));
        } catch (IOException e) {
            secrets = Map.of();
            secretsProblem = e.getMessage();
        }
        Map<String, RemoteConfig> remotes = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("remote.") && key.endsWith(".type")) {
                String name = key.substring(7, key.length() - 5);
                String p = "remote." + name + ".";
                Map<String, String> secret = secrets.getOrDefault(name, Map.of());
                remotes.put(name, new RemoteConfig(properties.getProperty(p + "type", ""),
                    Boolean.parseBoolean(properties.getProperty(p + "enabled", "true")), properties.getProperty(p + "path", ""),
                    properties.getProperty(p + "url", ""), properties.getProperty(p + "region", ""),
                    properties.getProperty(p + "bucket", ""), Boolean.parseBoolean(properties.getProperty(p + "path-style")),
                    properties.getProperty(p + "username", ""), secret.getOrDefault("password", ""),
                    properties.getProperty(p + "host", ""), Integer.parseInt(properties.getProperty(p + "port", "0")),
                    properties.getProperty(p + "private-key", ""), properties.getProperty(p + "host-key", ""),
                    properties.getProperty(p + "security", "explicit"), properties.getProperty(p + "client-id", ""),
                    secret.getOrDefault("client-secret", "")));
            }
        }
        String repository = properties.getProperty("repository");
        return new RestoreSettings(repository == null ? null : Path.of(repository), remotes, secretsProblem);
    }
}
