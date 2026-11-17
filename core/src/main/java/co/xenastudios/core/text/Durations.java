package co.xenastudios.core.text;

import java.time.Duration;
import java.util.Locale;

/**
 * Lengths of time as players type them: {@code 30m}, {@code 2w3d}, {@code 1.5h}. Units
 * are {@code w d h m s}; numbers may have a decimal point; units add up in any order.
 */
public final class Durations {

    private Durations() {}

    /**
     * Reads a length of time.
     *
     * @throws IllegalArgumentException with a message for the player, if it isn't one
     */
    public static Duration parse(String text) {
        String input = text.strip().toLowerCase(Locale.ROOT);
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Give a time, such as 30m, 6h or 2w3d.");
        }
        double seconds = 0;
        int i = 0;
        while (i < input.length()) {
            int start = i;
            while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
                i++;
            }
            if (i == start || i == input.length()) {
                throw new IllegalArgumentException("\"" + text + "\" isn't a time. Use a number then a unit: 30m, 6h, 2w3d.");
            }
            double amount;
            try {
                amount = Double.parseDouble(input.substring(start, i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + text + "\" isn't a time. Use a number then a unit: 30m, 6h, 2w3d.");
            }
            seconds += amount * switch (input.charAt(i)) {
                case 'w' -> 604_800;
                case 'd' -> 86_400;
                case 'h' -> 3_600;
                case 'm' -> 60;
                case 's' -> 1;
                default -> throw new IllegalArgumentException("\"" + input.charAt(i) + "\" isn't a unit of time. Use w, d, h, m or s.");
            };
            i++;
        }
        if (seconds > 100L * 365 * 86_400) {
            throw new IllegalArgumentException("\"" + text + "\" is more than 100 years.");
        }
        return Duration.ofMillis(Math.round(seconds * 1000));
    }

    /** A short form for messages: {@code 2w 3d}, {@code 4h 30m}, {@code 12s}. The two largest units. */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long[] sizes = {604_800, 86_400, 3_600, 60, 1};
        char[] units = {'w', 'd', 'h', 'm', 's'};
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int u = 0; u < sizes.length && shown < 2; u++) {
            long amount = seconds / sizes[u];
            if (amount > 0) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(amount).append(units[u]);
                seconds -= amount * sizes[u];
                shown++;
            } else if (shown > 0) {
                break;
            }
        }
        return out.isEmpty() ? "0s" : out.toString();
    }
}
