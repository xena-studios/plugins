package co.xenastudios.xvote.receiver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Votifier v2 tokens, one per server list by its service name, with {@code default} for
 * every list without its own. Kept in {@code tokens.yml} with the same {@code tokens:}
 * section NuVotifier's config has, so it can be pasted across either way, and out of
 * {@code config.yml}, which owners often share when asking for help.
 */
public final class Tokens {

    public static final String FILE = "tokens.yml";
    public static final String DEFAULT = "default";
    /**
     * Bukkit splits keys on dots, which would turn {@code MinecraftServers.org} into two
     * sections, so these files are read and written with a separator no name has.
     */
    static final char SEPARATOR = '\u0001';

    private static final String HEADER = """
        xVote's Votifier tokens, one per server list, by the name the list sends (its
        service name). "default" is used by every list without its own. Give a list its
        token when you add your server; /xvote token shows them.
        Keep these secret: anyone with a token can send votes as that list.
        This is NuVotifier's tokens section, so tokens can be copied across either way.""";

    private final Map<String, String> tokens;
    private final Map<String, String> byLowerName;

    private Tokens(Map<String, String> tokens) {
        this.tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        Map<String, String> lower = new LinkedHashMap<>();
        tokens.forEach((name, token) -> lower.putIfAbsent(name.toLowerCase(Locale.ROOT), token));
        this.byLowerName = lower;
    }

    /** Tokens from a map, such as NuVotifier's config. */
    public static Tokens of(Map<String, String> tokens) {
        return new Tokens(tokens);
    }

    /**
     * Reads {@code tokens.yml}, or writes one with a new {@code default} token if it's
     * missing or has no tokens.
     *
     * @return the tokens, and whether a new default was made (to tell the owner)
     */
    public static Loaded loadOrCreate(Path folder) throws IOException {
        Path file = folder.resolve(FILE);
        Map<String, String> read = new LinkedHashMap<>();
        if (Files.exists(file)) {
            YamlConfiguration yaml = yaml();
            try {
                yaml.load(file.toFile());
            } catch (InvalidConfigurationException e) {
                throw new IOException(FILE + " isn't valid YAML, so it was left as it is: " + e.getMessage(), e);
            }
            read.putAll(read(yaml.getConfigurationSection("tokens")));
        }
        if (!read.isEmpty()) {
            return new Loaded(new Tokens(read), false);
        }
        read.put(DEFAULT, Protocol.newToken());
        Tokens made = new Tokens(read);
        made.save(folder);
        return new Loaded(made, true);
    }

    /** A YAML file whose keys may have dots, as service names often do. */
    static YamlConfiguration yaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(SEPARATOR);
        return yaml;
    }

    /** The {@code tokens} section's names and tokens. */
    static Map<String, String> read(ConfigurationSection section) {
        Map<String, String> read = new LinkedHashMap<>();
        if (section != null) {
            for (String name : section.getKeys(false)) {
                Object value = section.get(name);
                if (value != null && !(value instanceof ConfigurationSection) && !value.toString().isBlank()) {
                    read.put(name, value.toString());
                }
            }
        }
        return read;
    }

    /** What {@link #loadOrCreate} found. */
    public record Loaded(Tokens tokens, boolean created) {}

    /** Writes {@code tokens.yml}, readable by the server's user only where the file system allows. */
    public void save(Path folder) throws IOException {
        Files.createDirectories(folder);
        YamlConfiguration yaml = yaml();
        yaml.options().setHeader(java.util.List.of(HEADER.split("\n")));
        tokens.forEach((name, token) -> yaml.set("tokens" + SEPARATOR + name, token));
        Path file = folder.resolve(FILE);
        Path temporary = folder.resolve(FILE + ".tmp");
        // Owner-only, like rsa/private.key: anyone with a token can send votes as that list.
        Secrets.write(temporary, yaml.saveToString());
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * A list's token: its own by exact name (as NuVotifier looks it up), then ignoring
     * case, or null if it has none. {@code default} is the caller's fallback.
     */
    public String forService(String service) {
        String exact = tokens.get(service);
        return exact != null ? exact : byLowerName.get(service.toLowerCase(Locale.ROOT));
    }

    /** Every token, by service name. */
    public Map<String, String> all() {
        return tokens;
    }

    /** These tokens plus a new one for the service, or with its token replaced. */
    public Tokens with(String service, String token) {
        Map<String, String> next = new LinkedHashMap<>(tokens);
        next.put(service, token);
        return new Tokens(next);
    }
}
