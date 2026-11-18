package co.xenastudios.xstats.stat;

import co.xenastudios.xstats.api.StatUnit;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Numbers as players read them: {@code 12,345}, {@code 3h 12m}, {@code 1.2 km},
 * {@code 4.5 hearts}. The same text goes to chat, placeholders and the website, so they
 * always agree.
 */
public final class Format {

    private static final ThreadLocal<DecimalFormat> WHOLE = ThreadLocal.withInitial(() -> pattern("#,##0"));
    private static final ThreadLocal<DecimalFormat> ONE = ThreadLocal.withInitial(() -> pattern("#,##0.#"));

    private Format() {}

    private static DecimalFormat pattern(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
    }

    public static String value(StatDef stat, long value) {
        return value(stat.unit(), stat.decimals(), value);
    }

    public static String value(StatUnit unit, int decimals, long value) {
        return switch (unit) {
            case COUNT -> decimals == 0 ? WHOLE.get().format(value)
                : pattern("#,##0." + "0".repeat(decimals)).format(BigDecimal.valueOf(value, decimals));
            case TIME -> time(value);
            case DISTANCE -> distance(value);
            case DAMAGE -> ONE.get().format(value / 20.0) + (value == 20 ? " heart" : " hearts");
        };
    }

    /** Seconds as {@code 45s}, {@code 12m}, {@code 3h 12m} or {@code 2d 4h}. */
    public static String time(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + minutes % 60 + "m";
        }
        return WHOLE.get().format(hours / 24) + "d " + hours % 24 + "h";
    }

    /** Centimetres as {@code 350 m} or {@code 1.2 km}. */
    public static String distance(long centimetres) {
        long metres = centimetres / 100;
        if (metres < 1000) {
            return metres + " m";
        }
        return (metres < 100_000 ? ONE.get().format(metres / 1000.0) : WHOLE.get().format(metres / 1000)) + " km";
    }

    /** Kills per death, to two places: 2.50, or the kills when there are no deaths. */
    public static String ratio(long kills, long deaths) {
        double ratio = deaths == 0 ? kills : (double) kills / deaths;
        return String.format(Locale.ROOT, "%.2f", ratio);
    }
}
