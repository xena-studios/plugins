package co.xenastudios.xvote.receiver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Takes over NuVotifier's setup: its RSA keys, its tokens, and the port it listened on,
 * so every list keeps working without the owner touching a thing. NuVotifier calls its
 * folder {@code Votifier}; some installs renamed it.
 */
public final class NuVotifierImport {

    /** Folders NuVotifier may live in, relative to the plugins folder. */
    static final List<String> FOLDERS = List.of("Votifier", "NuVotifier");

    private NuVotifierImport() {}

    /**
     * What was read from NuVotifier.
     *
     * @param port the port it listened on, or 0 if its config didn't say
     * @param host the address it listened on, or empty
     * @param v1 whether it accepted v1 votes
     */
    public record Found(Path folder, KeyPair keys, Tokens tokens, String host, int port, boolean v1) {}

    /** NuVotifier's folder in the plugins folder, or null if there's none with keys or a config. */
    public static Path find(Path pluginsFolder) {
        for (String name : FOLDERS) {
            Path folder = pluginsFolder.resolve(name);
            if (Keys.exist(folder.resolve("rsa")) || Files.isRegularFile(folder.resolve("config.yml"))) {
                return folder;
            }
        }
        return null;
    }

    /**
     * Reads NuVotifier's keys and config.
     *
     * @throws IOException if they can't be read, with a reason for the owner
     */
    public static Found read(Path folder) throws IOException {
        KeyPair keys = Keys.exist(folder.resolve("rsa")) ? Keys.load(folder.resolve("rsa")) : null;
        Map<String, String> tokens = new LinkedHashMap<>();
        String host = "";
        int port = 0;
        boolean v1 = true;
        Path config = folder.resolve("config.yml");
        if (Files.isRegularFile(config)) {
            YamlConfiguration yaml = Tokens.yaml();
            try {
                yaml.load(config.toFile());
            } catch (InvalidConfigurationException e) {
                throw new IOException("NuVotifier's config.yml isn't valid YAML: " + e.getMessage(), e);
            }
            tokens.putAll(Tokens.read(yaml.getConfigurationSection("tokens")));
            host = yaml.getString("host", "");
            port = yaml.getInt("port", 0);
            v1 = !yaml.getBoolean("disable-v1-protocol", false);
        }
        if (keys == null && tokens.isEmpty()) {
            throw new IOException("NuVotifier's folder " + folder.getFileName() + " has no rsa keys and no tokens.");
        }
        return new Found(folder, keys, tokens.isEmpty() ? null : Tokens.of(tokens), host == null ? "" : host, port, v1);
    }

    /** Writes what was found into xVote's folder: {@code rsa/} and {@code tokens.yml}. */
    public static void write(Found found, Path xvoteFolder) throws IOException {
        if (found.keys() != null) {
            Keys.save(xvoteFolder.resolve("rsa"), found.keys());
        }
        if (found.tokens() != null) {
            found.tokens().save(xvoteFolder);
        }
    }
}
