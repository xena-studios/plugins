package co.xenastudios.xbackup.server;

import co.xenastudios.core.config.ConfigException;
import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.xbackup.remote.OwnerOnlyFile;
import co.xenastudios.xbackup.remote.SecretFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The remotes' passwords and secret keys, kept in {@value #NAME} beside config.yml instead of
 * in it. Owners share their config.yml for help and copy it between servers, and xBackup backs
 * it up and copies it to every remote. This file is readable by the server's user only, never
 * backed up, never copied, and never shown.
 *
 * <pre>
 * offsite:
 *   password: "..."
 * gdrive:
 *   client-secret: "..."
 * </pre>
 *
 * <p>A password or client secret found in config.yml (from an older xBackup build, or typed
 * there by habit) is moved here, and removed from config.yml, each time the config loads.
 */
public final class Secrets {

    public static final String NAME = SecretFile.SECRETS;
    private static final List<String> KEYS = List.of("password", "client-secret");

    private static final String HEADER = """
        Passwords and secret keys for the remotes in config.yml, by remote name. Only the
        server's user can read this file, and xBackup never backs it up or copies it to a
        remote. For example:

        offsite:
          password: "..."      # s3: the secret key; sftp, ftp, webdav: the password
        gdrive:
          client-secret: "..." # drive: your app's client secret
        """;

    private Secrets() {}

    /** One remote's secrets. Blank if not set. */
    public record Entry(String password, String clientSecret) {
        static final Entry NONE = new Entry("", "");
    }

    /**
     * Moves any secret in config.yml's remotes into {@value #NAME}, then takes it out of
     * config.yml. A value in config.yml replaces the one here: it's the newer.
     *
     * @return the remotes whose secrets moved
     */
    public static List<String> migrate(Path home, Path configFile, Logger logger) {
        if (!Files.isRegularFile(configFile)) {
            return List.of();
        }
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(Files.readString(configFile, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            // The config's own load reports it.
            return List.of();
        }
        ConfigurationSection remotes = config.getConfigurationSection("remotes");
        if (remotes == null) {
            return List.of();
        }
        Map<String, Map<String, String>> found = new LinkedHashMap<>();
        for (String name : remotes.getKeys(false)) {
            ConfigurationSection remote = remotes.getConfigurationSection(name);
            if (remote == null) {
                continue;
            }
            for (String key : KEYS) {
                if (remote.contains(key)) {
                    String value = remote.getString(key, "");
                    found.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(key, value == null ? "" : value);
                }
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        Path file = home.resolve(NAME);
        try {
            YamlConfiguration secrets = load(file);
            List<String> moved = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> remote : found.entrySet()) {
                boolean any = false;
                for (Map.Entry<String, String> value : remote.getValue().entrySet()) {
                    if (!value.getValue().isBlank()) {
                        secrets.set(remote.getKey() + "." + value.getKey(), value.getValue());
                        any = true;
                    }
                }
                if (any) {
                    moved.add(remote.getKey());
                }
            }
            // Stored safely before config.yml loses them.
            write(file, secrets);
            ConfigLoader.edit(configFile, yaml -> {
                ConfigurationSection section = yaml.getConfigurationSection("remotes");
                boolean changed = false;
                for (Map.Entry<String, Map<String, String>> remote : found.entrySet()) {
                    for (String key : remote.getValue().keySet()) {
                        if (section != null && section.contains(remote.getKey() + "." + key)) {
                            section.set(remote.getKey() + "." + key, null);
                            changed = true;
                        }
                    }
                }
                return changed;
            });
            if (!moved.isEmpty()) {
                logger.info("Moved the passwords of " + String.join(", ", moved) + " from config.yml to " + NAME
                    + ", which only the server's user can read and which is never backed up. Backups taken before"
                    + " hold config.yml with them in it, and so do the remotes' copies: change those passwords if"
                    + " anyone else could read the backups.");
            }
            return moved;
        } catch (IOException | ConfigException e) {
            logger.warning("Couldn't move the remotes' passwords from config.yml to " + NAME + ": " + e.getMessage()
                + " They're still used from config.yml, and config.yml is still backed up with them in it.");
            return List.of();
        }
    }

    /**
     * Reads every remote's secrets, and the passwords still in config.yml if moving them
     * failed. Makes the file owner-only if it wasn't.
     */
    public static Map<String, Entry> read(Path home, Path configFile, Logger logger) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Path file = home.resolve(NAME);
        try {
            if (Files.exists(file)) {
                OwnerOnlyFile.restrict(file);
                YamlConfiguration secrets = load(file);
                try {
                    SecretFile.read(file);
                } catch (IOException e) {
                    logger.warning("The command-line restore can't read " + NAME + ": " + e.getMessage());
                }
                for (String name : secrets.getKeys(false)) {
                    ConfigurationSection section = secrets.getConfigurationSection(name);
                    if (section != null) {
                        entries.put(name, new Entry(text(section, "password"), text(section, "client-secret")));
                    }
                }
            } else {
                write(file, new YamlConfiguration());
            }
        } catch (IOException e) {
            logger.warning("Couldn't read " + file + ": " + e.getMessage() + " Remotes that need a password won't connect.");
        }
        // Left in config.yml because moving them failed: still used, rather than every copy failing.
        if (Files.isRegularFile(configFile)) {
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.loadFromString(Files.readString(configFile, StandardCharsets.UTF_8));
                ConfigurationSection remotes = config.getConfigurationSection("remotes");
                if (remotes != null) {
                    for (String name : remotes.getKeys(false)) {
                        ConfigurationSection remote = remotes.getConfigurationSection(name);
                        if (remote != null && (remote.contains("password") || remote.contains("client-secret"))) {
                            Entry stored = entries.getOrDefault(name, Entry.NONE);
                            entries.put(name, new Entry(or(text(remote, "password"), stored.password()),
                                or(text(remote, "client-secret"), stored.clientSecret())));
                        }
                    }
                }
            } catch (IOException | InvalidConfigurationException e) {
                // The config's own load reports it.
            }
        }
        return entries;
    }

    private static String or(String first, String second) {
        return first.isBlank() ? second : first;
    }

    private static String text(ConfigurationSection section, String key) {
        String value = section.getString(key, "");
        return value == null ? "" : value;
    }

    private static YamlConfiguration load(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.exists(file)) {
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException(file.getFileName() + " isn't valid YAML: " + e.getMessage().lines().findFirst()
                    .orElse(""), e);
            }
        }
        return yaml;
    }

    // Written plainly, every value quoted on one line, so the command-line restore reads it
    // without a server (SecretFile).
    private static void write(Path file, YamlConfiguration yaml) throws IOException {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        for (String name : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            Map<String, String> remote = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                if (!section.isConfigurationSection(key)) {
                    remote.put(key, String.valueOf(section.get(key)));
                }
            }
            values.put(name, remote);
        }
        SecretFile.write(file, HEADER, values);
    }
}
