package co.xenastudios.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Loads a config file against its {@link ConfigSpec}. */
public final class ConfigLoader {

    private static final Pattern BLANK_WITH_SPACES = Pattern.compile("(?m)^[ \\t]+$");

    private ConfigLoader() {}

    /**
     * What loading found.
     *
     * @param config the settings to use
     * @param warnings settings that couldn't be used and fell back to their defaults
     * @param notices what the loader changed in the file, such as adding new settings
     */
    public record Result<C>(C config, List<String> warnings, List<String> notices) {}

    /** The default config file, as written on first start. */
    public static String defaultText(ConfigSpec<?> spec) {
        return Emitter.render(spec);
    }

    /**
     * Reads the file, writing the defaults first if it doesn't exist. An old file is
     * backed up, carried forward, and saved with any new settings added: the owner's
     * values are kept, and the plugin's own comments are brought up to date. A file
     * from a newer version is never written.
     *
     * @throws ConfigException if the file isn't valid YAML; it's left untouched
     */
    public static <C extends Record> Result<C> load(ConfigSpec<C> spec, Path file) throws ConfigException {
        List<String> notices = new ArrayList<>();
        try {
            if (Files.notExists(file)) {
                write(file, Emitter.render(spec));
                notices.add("Wrote the default " + file.getFileName() + ".");
                return new Result<>(spec.defaults, List.of(), notices);
            }

            String text = Files.readString(file, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(text);
            } catch (InvalidConfigurationException e) {
                throw new ConfigException(file.getFileName() + " isn't valid YAML: " + firstLine(e), e);
            }

            if (spec.foreign != null && spec.foreign.test(yaml)) {
                Path aside = file.resolveSibling(file.getFileName() + ".old");
                for (int n = 2; Files.exists(aside); n++) {
                    aside = file.resolveSibling(file.getFileName() + ".old-" + n);
                }
                Files.move(file, aside);
                write(file, Emitter.render(spec));
                notices.add(spec.foreignNotice + " It's kept as " + aside.getFileName() + ", and the defaults are in " + file.getFileName() + ".");
                return new Result<>(spec.defaults, List.of(), notices);
            }

            List<String> warnings = new ArrayList<>();
            int version = spec.version;
            Object rawVersion = yaml.get(Writer.VERSION_KEY);
            if (rawVersion instanceof Integer v) {
                version = v;
            } else if (rawVersion != null) {
                warnings.add(Writer.VERSION_KEY + " isn't a whole number; treating the config as current.");
            }

            boolean changed = false;
            boolean newer = version > spec.version;
            if (newer) {
                warnings.add("%s is from a newer version of the plugin (config version %d; this one reads %d). Settings it doesn't know are ignored, and the file is left as it is."
                    .formatted(file.getFileName(), version, spec.version));
            } else if (version < spec.version) {
                Path backup = backup(file, version);
                for (Map.Entry<Integer, Migration> step : spec.migrations.tailMap(Math.max(version, 1)).entrySet()) {
                    step.getValue().apply(yaml);
                }
                yaml.set(Writer.VERSION_KEY, spec.version);
                int refreshed = Writer.refreshDefaults(spec, yaml);
                if (refreshed > 0) {
                    notices.add("Gave %d setting%s you hadn't changed the new default%s."
                        .formatted(refreshed, refreshed == 1 ? "" : "s", refreshed == 1 ? "" : "s"));
                }
                Writer.recomment(spec, yaml);
                notices.add("Carried %s forward from config version %d to %d. The old file is %s."
                    .formatted(file.getFileName(), version, spec.version, backup.getFileName()));
                changed = true;
            }

            Reader reader = new Reader(spec, new Yaml(new SafeConstructor(new LoaderOptions())).load(text));
            C config = reader.readRoot(spec.type, yaml, spec.defaults);
            warnings.addAll(reader.warnings);

            if (!newer) {
                if (!yaml.contains(Writer.VERSION_KEY)) {
                    yaml.set(Writer.VERSION_KEY, spec.version);
                    changed = true;
                }
                // Bukkit would drop "permission:" on save; keep it as an explicit blank.
                for (Reader.Blank blank : reader.blanks) {
                    if (blank.value() instanceof Map<?, ?>) {
                        blank.parent().createSection(blank.key());
                    } else {
                        blank.parent().set(blank.key(), blank.value() instanceof List<?> ? new ArrayList<>() : "");
                    }
                }
                List<String> added = new ArrayList<>();
                for (Reader.Missing missing : reader.missing) {
                    Writer.add(spec, missing.parent(), missing.key(), missing.component(), missing.value());
                    String path = missing.parent().getCurrentPath();
                    added.add((path == null || path.isEmpty() ? "" : path + ".") + missing.key());
                    changed = true;
                }
                if (added.size() == 1) {
                    notices.add("Added " + added.getFirst() + " with its default.");
                } else if (!added.isEmpty()) {
                    notices.add("Added " + added.size() + " new settings with their defaults: " + String.join(", ", added) + ".");
                }
                if (changed) {
                    // Bukkit indents the blank lines it writes; trim them.
                    write(file, BLANK_WITH_SPACES.matcher(yaml.saveToString()).replaceAll(""));
                }
            }
            return new Result<>(config, List.copyOf(warnings), List.copyOf(notices));
        } catch (IOException e) {
            throw new ConfigException("couldn't read or write " + file + ": " + e.getMessage(), e);
        }
    }

    // config.yml -> config.yml.bak-v1, or config.yml.bak-v1-2 if that exists.
    private static Path backup(Path file, int version) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak-v" + version);
        for (int n = 2; Files.exists(backup); n++) {
            backup = file.resolveSibling(file.getFileName() + ".bak-v" + version + "-" + n);
        }
        Files.copy(file, backup);
        return backup;
    }

    // Writes beside the file and swaps it in, so a crash never leaves half a config.
    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String firstLine(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = String.valueOf(cause.getMessage()).strip();
        return message.lines().limit(4).reduce((a, b) -> a + " " + b.strip()).orElse(message);
    }
}
