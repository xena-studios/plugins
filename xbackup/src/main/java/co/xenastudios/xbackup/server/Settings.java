package co.xenastudios.xbackup.server;

import co.xenastudios.core.text.Durations;
import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.store.Retention;
import co.xenastudios.xbackup.store.Sizes;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The config, read once into the forms the engine uses: durations, sizes, paths. A value
 * that doesn't read falls back to its default with a warning, like every other setting.
 *
 * @param every how often backups run; null when the schedule is off
 * @param verifyEvery how often older data is read back; null for never
 */
public record Settings(Path repository, Duration every, boolean skipWhenIdle, XBConfig.Sources sources,
                       List<String> excludes, Retention retention, long maxSize, long minFree, long readLimit,
                       long uploadLimit, int level, Duration maxPause, Duration verifyEvery, int verifyPercent) {

    public static Settings of(XBConfig config, Path root, Consumer<String> warn) {
        XBConfig defaults = XBConfig.DEFAULTS;
        Path repository = root.resolve(config.repository().isBlank() ? defaults.repository() : config.repository().strip())
            .toAbsolutePath().normalize();
        Duration every = duration(config.schedule().every(), defaults.schedule().every(), "schedule.every", warn);
        if (every != null && every.compareTo(Duration.ofMinutes(5)) < 0) {
            warn.accept("schedule.every is less than 5 minutes; using 5m.");
            every = Duration.ofMinutes(5);
        }
        Duration maxPause = duration(config.performance().maxPause(), defaults.performance().maxPause(),
            "performance.max-pause", warn);
        if (maxPause == null || maxPause.compareTo(Duration.ofMinutes(1)) < 0) {
            maxPause = Duration.ofMinutes(1);
        }
        List<String> excludes = new ArrayList<>();
        for (String pattern : config.exclude()) {
            try {
                co.xenastudios.xbackup.backup.Excludes.check(pattern);
                excludes.add(pattern);
            } catch (IllegalArgumentException e) {
                warn.accept("exclude: " + e.getMessage() + "; that pattern is ignored.");
            }
        }
        XBConfig.Retention keep = config.retention();
        return new Settings(repository, every, config.schedule().skipWhenIdle(), config.sources(), excludes,
            new Retention(keep.last(), keep.hourly(), keep.daily(), keep.weekly(), keep.monthly()),
            size(keep.maxSize(), defaults.retention().maxSize(), "retention.max-size", warn),
            size(keep.minFreeSpace(), defaults.retention().minFreeSpace(), "retention.min-free-space", warn),
            size(config.performance().readLimit(), "off", "performance.read-limit", warn),
            size(config.performance().uploadLimit(), "off", "performance.upload-limit", warn),
            config.performance().compressionLevel(), maxPause,
            duration(config.verify().every(), defaults.verify().every(), "verify.every", warn), config.verify().percent());
    }

    private static Duration duration(String text, String fallback, String key, Consumer<String> warn) {
        String value = text.strip().toLowerCase(Locale.ROOT);
        if (value.equals("off") || value.equals("never") || value.equals("0")) {
            return null;
        }
        try {
            return Durations.parse(value);
        } catch (IllegalArgumentException e) {
            warn.accept(key + ": " + e.getMessage() + " Using " + fallback + ".");
            return fallback.equals("off") ? null : Durations.parse(fallback);
        }
    }

    private static long size(String text, String fallback, String key, Consumer<String> warn) {
        try {
            return Sizes.parse(text);
        } catch (IllegalArgumentException e) {
            warn.accept(key + ": " + e.getMessage() + " Using " + fallback + ".");
            return Sizes.parse(fallback);
        }
    }
}
