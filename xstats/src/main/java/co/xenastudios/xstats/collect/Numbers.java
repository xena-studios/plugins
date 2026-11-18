package co.xenastudios.xstats.collect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numbers out of placeholder text: {@code 1,234.56}, {@code $12.5k}, {@code §a42}, {@code &#FF0000 42}. */
public final class Numbers {

    /**
     * Colour and format codes, which hold digits that aren't the number: hex colours
     * ({@code &#RRGGBB}, {@code {#RRGGBB}}, Spigot's {@code &x&R&R&G&G&B&B}), MiniMessage and
     * other tags in angle brackets, then legacy codes ({@code §a}, {@code &l}).
     */
    private static final Pattern CODES = Pattern.compile(
        "(?i)[§&]#[0-9a-f]{6}|\\{#[0-9a-f]{6}}|[§&]x(?:[§&][0-9a-f]){6}|<[^<>]*>|[§&][0-9a-fk-orx]");

    /**
     * The first number, and a k, m, b or t right after it (no space) that isn't the start of
     * a word: {@code 12.5k} is 12,500, while {@code 5 blocks}, {@code 12 kills} and
     * {@code 3mins} are 5, 12 and 3.
     */
    private static final Pattern NUMBER = Pattern.compile("(?i)(-?)(\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+)([kmbt](?![a-z]))?");

    private Numbers() {}

    /**
     * The number in a placeholder's text, with {@code decimals} places kept as a whole number
     * ({@code "12.34"} with 2 decimals is 1234), or null if there's no number.
     */
    public static Long parse(String text, int decimals) {
        if (text == null) {
            return null;
        }
        Matcher number = NUMBER.matcher(CODES.matcher(text).replaceAll(""));
        if (!number.find()) {
            return null;
        }
        String digits = number.group(1) + number.group(2).replace(",", "");
        String suffix = number.group(3);
        long multiplier = suffix == null ? 1 : switch (Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b' -> 1_000_000_000L;
            default -> 1_000_000_000_000L;
        };
        try {
            BigDecimal value = new BigDecimal(digits).multiply(BigDecimal.valueOf(multiplier));
            return value.setScale(decimals, RoundingMode.HALF_UP).unscaledValue().longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
