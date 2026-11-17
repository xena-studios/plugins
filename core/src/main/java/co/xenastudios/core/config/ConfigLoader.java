package co.xenastudios.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
     * @param carriedFrom the config version the file was carried forward from, or 0 if it wasn't
     */
    public record Result<C>(C config, List<String> warnings, List<String> notices, int carriedFrom) {}

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
        return load(spec, file, true);
    }

    /**
     * Reads the settings the file holds, carried forward in memory if it's old, without
     * writing, moving or backing up anything: for a peek before the plugin's own load,
     * which then does those once.
     *
     * @throws ConfigException if the file isn't valid YAML
     */
    public static <C extends Record> Result<C> read(ConfigSpec<C> spec, Path file) throws ConfigException {
        return load(spec, file, false);
    }

    private static <C extends Record> Result<C> load(ConfigSpec<C> spec, Path file, boolean mayWrite) throws ConfigException {
        List<String> notices = new ArrayList<>();
        try {
            if (Files.notExists(file) && !mayWrite) {
                return new Result<>(spec.defaults, List.of(), notices, 0);
            }
            if (Files.notExists(file)) {
                write(file, Emitter.render(spec));
                notices.add("Wrote the default " + file.getFileName() + ".");
                return new Result<>(spec.defaults, List.of(), notices, 0);
            }

            String text = Files.readString(file, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(text);
            } catch (InvalidConfigurationException e) {
                throw new ConfigException(file.getFileName() + " isn't valid YAML: " + firstLine(e), e);
            }

            if (spec.foreign != null && spec.foreign.test(yaml) && !mayWrite) {
                return new Result<>(spec.defaults, List.of(), notices, 0);
            }
            if (spec.foreign != null && spec.foreign.test(yaml)) {
                Path aside = file.resolveSibling(file.getFileName() + ".old");
                for (int n = 2; Files.exists(aside); n++) {
                    aside = file.resolveSibling(file.getFileName() + ".old-" + n);
                }
                Files.move(file, aside);
                write(file, Emitter.render(spec));
                notices.add(spec.foreignNotice + " It's kept as " + aside.getFileName() + ", and the defaults are in " + file.getFileName() + ".");
                return new Result<>(spec.defaults, List.of(), notices, 0);
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
            int carriedFrom = 0;
            Path backup = null;
            boolean newer = version > spec.version;
            // A newer plugin's file, or one that can't be backed up, is read but never rewritten.
            boolean leaveFile = newer || !mayWrite;
            if (newer) {
                warnings.add("%s is from a newer version of the plugin (config version %d; this one reads %d). Settings it doesn't know are ignored, and the file is left as it is."
                    .formatted(file.getFileName(), version, spec.version));
            } else if (version < spec.version) {
                try {
                    if (mayWrite) {
                        backup = backup(file, version);
                    }
                } catch (IOException e) {
                    // Can't keep a copy (a read-only folder, say): carry it forward in memory only,
                    // and leave the file alone below so nothing is lost.
                    warnings.add("couldn't back up %s (%s), so it's carried forward for this run only and left unchanged on disk."
                        .formatted(file.getFileName(), e.getMessage()));
                    leaveFile = true;
                }
                carriedFrom = version;
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
                if (backup != null) {
                    notices.add("Carried %s forward from config version %d to %d. The old file is %s."
                        .formatted(file.getFileName(), version, spec.version, backup.getFileName()));
                }
                changed = true;
            }

            Reader reader = new Reader(spec, new Yaml(new SafeConstructor(new LoaderOptions())).load(text));
            C config = reader.readRoot(spec.type, yaml, spec.defaults);
            warnings.addAll(reader.warnings);

            if (!leaveFile) {
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
                    try {
                        // Bukkit indents the blank lines it writes; trim them.
                        write(file, BLANK_WITH_SPACES.matcher(yaml.saveToString()).replaceAll(""));
                    } catch (IOException e) {
                        // A file that can't be written back (a read-only folder, say) still says
                        // what the owner wants: run with it, and say the file wasn't updated.
                        if (backup != null) {
                            // Its backup would only pile up, one more on every start.
                            Files.deleteIfExists(backup);
                            notices.removeIf(notice -> notice.startsWith("Carried "));
                        }
                        warnings.add("couldn't save the updated %s (%s), so it's unchanged on disk; these settings are in use anyway."
                            .formatted(file.getFileName(), e.getMessage()));
                    }
                }
            }
            return new Result<>(config, List.copyOf(warnings), List.copyOf(notices), carriedFrom);
        } catch (IOException e) {
            throw new ConfigException("couldn't read or write " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Changes the file in place, as a command editing a setting would: the owner's other
     * values and comments are kept, and the file is swapped in whole. Reload afterwards to
     * apply it. Call from one thread at a time (the global thread).
     *
     * @param change makes the change, returning false if there was nothing to change
     * @return whether the file was written
     * @throws ConfigException if the file isn't valid YAML or can't be written; it's left untouched
     */
    public static boolean edit(Path file, java.util.function.Predicate<YamlConfiguration> change) throws ConfigException {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            if (Files.exists(file)) {
                try {
                    yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                } catch (InvalidConfigurationException e) {
                    throw new ConfigException(file.getFileName() + " isn't valid YAML: " + firstLine(e), e);
                }
            }
            if (!change.test(yaml)) {
                return false;
            }
            write(file, BLANK_WITH_SPACES.matcher(yaml.saveToString()).replaceAll(""));
            return true;
        } catch (IOException e) {
            throw new ConfigException("couldn't write " + file + ": " + e.getMessage(), e);
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
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some mounted filesystems (network shares, some container volumes) can't swap
                // atomically; a plain replace is the best they offer.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String firstLine(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = String.valueOf(cause.getMessage()).strip();
        return message.lines().limit(4).reduce((a, b) -> a + " " + b.strip()).orElse(message);
    }
}
