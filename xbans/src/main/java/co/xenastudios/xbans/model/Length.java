package co.xenastudios.xbans.model;

import co.xenastudios.core.config.Codec;
import co.xenastudios.core.text.Durations;
import java.time.Duration;
import java.util.Locale;

/**
 * How long a punishment lasts, as staff type it the LiteBans way: {@code 30m}, {@code 1d2h},
 * {@code 1.5h}, {@code 2mo}, {@code 1y}, {@code 5 days}, or {@code perm}. Units are
 * {@code s m h d w mo y}; a month is 30 days and a year 365.
 *
 * @param millis how long, in milliseconds; 0 means permanent
 */
public record Length(long millis) implements Comparable<Length> {

    public static final Length PERMANENT = new Length(0);
    /** The longest a punishment can last before it counts as permanent. */
    static final long MAX_MILLIS = 100L * 365 * 86_400_000L;

    public boolean permanent() {
        return millis == 0;
    }

    /** The length as a Duration, or null when it's permanent. */
    public Duration duration() {
        return permanent() ? null : Duration.ofMillis(millis);
    }

    public static Length of(Duration duration) {
        return duration == null ? PERMANENT : new Length(Math.max(1, duration.toMillis()));
    }

    /** Whether this is longer than {@code other}, permanent being longest. */
    @Override
    public int compareTo(Length other) {
        if (permanent() || other.permanent()) {
            return Boolean.compare(permanent(), other.permanent());
        }
        return Long.compare(millis, other.millis);
    }

    /** As typed: {@code perm}, or the short form such as {@code 7d} or {@code 1w3d}. */
    public String typed() {
        return permanent() ? "permanent" : Durations.format(Duration.ofMillis(millis)).replace(" ", "");
    }

    /** As shown in messages: {@code 7d}, {@code 1w 3d}. Permanent is the caller's word. */
    public String shown() {
        return Durations.format(Duration.ofMillis(millis));
    }

    /** Whether the word is one of the ways to say "permanent". */
    public static boolean isPermanentWord(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "perm", "permanent", "permanently", "forever", "never", "-1", "duration:permanent", "duration:perm" -> true;
            default -> false;
        };
    }

    /**
     * Reads a length, or returns null if the text isn't one (so a reason can start there).
     *
     * @throws IllegalArgumentException if it looks like a length but goes past 100 years
     */
    public static Length parse(String text) {
        String input = text.strip().toLowerCase(Locale.ROOT);
        if (input.startsWith("duration:")) {
            input = input.substring("duration:".length());
        }
        if (isPermanentWord(input)) {
            return PERMANENT;
        }
        if (input.isEmpty() || !Character.isDigit(input.charAt(0)) && input.charAt(0) != '.') {
            return null;
        }
        double total = 0;
        int i = 0;
        while (i < input.length()) {
            int start = i;
            while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
                i++;
            }
            if (i == start || i == input.length()) {
                return null;
            }
            double amount;
            try {
                amount = Double.parseDouble(input.substring(start, i));
            } catch (NumberFormatException e) {
                return null;
            }
            int unitStart = i;
            while (i < input.length() && Character.isLetter(input.charAt(i))) {
                i++;
            }
            long unit = unit(input.substring(unitStart, i));
            if (unit == 0) {
                return null;
            }
            total += amount * unit;
        }
        if (total < 1000) {
            return null;
        }
        if (total > MAX_MILLIS) {
            throw new IllegalArgumentException("\"" + text + "\" is more than 100 years. Use perm for permanent.");
        }
        return new Length(Math.round(total));
    }

    /** A unit's length in milliseconds, long and short names alike, or 0 if it isn't one. */
    static long unit(String unit) {
        return switch (unit) {
            case "s", "sec", "secs", "second", "seconds" -> 1_000L;
            case "m", "min", "mins", "minute", "minutes" -> 60_000L;
            case "h", "hr", "hrs", "hour", "hours" -> 3_600_000L;
            case "d", "day", "days" -> 86_400_000L;
            case "w", "wk", "wks", "week", "weeks" -> 604_800_000L;
            case "mo", "mon", "month", "months" -> 30 * 86_400_000L;
            case "y", "yr", "yrs", "year", "years" -> 365 * 86_400_000L;
            default -> 0;
        };
    }

    /** Whether a word is a unit of time on its own, for "5 days" typed with a space. */
    public static boolean isUnit(String word) {
        return unit(word.toLowerCase(Locale.ROOT)) > 0;
    }

    public static final Codec<Length> CODEC = new Codec<>() {
        @Override
        public Length decode(Object raw) {
            Length length;
            try {
                length = parse(raw.toString());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("is more than 100 years; write permanent instead");
            }
            if (length == null) {
                throw new IllegalArgumentException("isn't a length of time, such as 7d, 1d12h, 1mo or permanent");
            }
            return length;
        }

        @Override
        public Object encode(Length value) {
            return value.typed();
        }
    };
}
