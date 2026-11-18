package co.xenastudios.xbackup.store;

import java.util.Locale;

/** Sizes as people write them: {@code 512MB}, {@code 2GB}, {@code 1.5TB}. Units are powers of 1024. */
public final class Sizes {

    private Sizes() {}

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    /** A short form: {@code 1.4 GB}, {@code 812 KB}, {@code 0 B}. */
    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s", value, UNITS[unit]);
    }

    /**
     * Reads a size. A plain number is bytes; units are B, KB, MB, GB and TB (K, M, G and T
     * too), in any case. {@code 0} and {@code off} are 0.
     *
     * @throws IllegalArgumentException with a message for the owner, if it isn't one
     */
    public static long parse(String text) {
        String input = text.strip().toUpperCase(Locale.ROOT).replace(" ", "");
        if (input.isEmpty() || input.equals("OFF") || input.equals("NONE")) {
            return 0;
        }
        int end = 0;
        while (end < input.length() && (Character.isDigit(input.charAt(end)) || input.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a size. Write a number and a unit: 500MB, 20GB.");
        }
        double number;
        try {
            number = Double.parseDouble(input.substring(0, end));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a size. Write a number and a unit: 500MB, 20GB.");
        }
        String unit = input.substring(end);
        if (unit.endsWith("IB")) {
            unit = unit.substring(0, unit.length() - 2) + "B";
        }
        long scale = switch (unit) {
            case "", "B" -> 1L;
            case "K", "KB" -> 1L << 10;
            case "M", "MB" -> 1L << 20;
            case "G", "GB" -> 1L << 30;
            case "T", "TB" -> 1L << 40;
            default -> throw new IllegalArgumentException("\"" + text + "\" has an unknown unit. Use KB, MB, GB or TB.");
        };
        double bytes = number * scale;
        if (bytes > Long.MAX_VALUE / 2) {
            throw new IllegalArgumentException("\"" + text + "\" is too big.");
        }
        return Math.round(bytes);
    }
}
