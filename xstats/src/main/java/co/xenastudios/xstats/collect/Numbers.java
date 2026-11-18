package co.xenastudios.xstats.collect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Numbers out of placeholder text: {@code 1,234.56}, {@code $12.5k}, {@code §a42}. */
public final class Numbers {

    private Numbers() {}

    /**
     * The number in a placeholder's text, with {@code decimals} places kept as a whole number
     * ({@code "12.34"} with 2 decimals is 1234), or null if there's no number. A k, m, b or t
     * after it multiplies by a thousand, million, billion or trillion.
     */
    public static Long parse(String text, int decimals) {
        if (text == null) {
            return null;
        }
        // Colour codes, then anything that isn't part of a number.
        String plain = text.replaceAll("(?i)[§&][0-9a-fk-orx]", "").replace(",", "").strip().toLowerCase(Locale.ROOT);
        StringBuilder digits = new StringBuilder();
        int i = 0;
        while (i < plain.length() && !(Character.isDigit(plain.charAt(i)) || plain.charAt(i) == '-' || plain.charAt(i) == '.')) {
            i++;
        }
        while (i < plain.length() && (Character.isDigit(plain.charAt(i)) || plain.charAt(i) == '.'
            || plain.charAt(i) == '-' && digits.isEmpty())) {
            digits.append(plain.charAt(i++));
        }
        String rest = plain.substring(i).strip();
        long multiplier = rest.isEmpty() ? 1 : switch (rest.charAt(0)) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b' -> 1_000_000_000L;
            case 't' -> 1_000_000_000_000L;
            default -> 1;
        };
        try {
            BigDecimal value = new BigDecimal(digits.toString()).multiply(BigDecimal.valueOf(multiplier));
            return value.setScale(decimals, RoundingMode.HALF_UP).unscaledValue().longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
