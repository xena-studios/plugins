package co.xenastudios.xmoney.money;

import co.xenastudios.xmoney.config.XMConfig;
import java.math.BigDecimal;
import java.util.List;

/**
 * How the currency looks and how amounts are typed, from the config's {@code currency}
 * section. Immutable, so any thread may use it; a reload makes a new one.
 */
public final class Format {

    private static final String[] DEFAULT_SUFFIXES = {"k", "M", "B", "T"};

    private final XMConfig.CurrencySettings settings;
    private final String[] suffixes;

    public Format(XMConfig.CurrencySettings settings) {
        this.settings = settings;
        List<String> configured = settings.shortSuffixes();
        this.suffixes = configured.size() == 4 ? configured.toArray(String[]::new) : DEFAULT_SUFFIXES;
    }

    public XMConfig.CurrencySettings settings() {
        return settings;
    }

    public int decimals() {
        return settings.decimals();
    }

    /** The amount as the config shows it: {@code $1,234.50}, or {@code 1,234.50 dollars}. */
    public String format(long units) {
        String number = grouped(units);
        boolean one = units == Money.ONE;
        return settings.format()
            .replace("{amount}", number)
            .replace("{symbol}", settings.symbol())
            .replace("{name}", one ? settings.singular() : settings.plural());
    }

    /** The bare number with the currency's decimals and no grouping: {@code 1234.50}, for placeholders. */
    public String plain(long units) {
        return Money.decimal(units, settings.decimals()).toPlainString();
    }

    /** The number with grouping and the currency's separators: {@code 1,234.50}. */
    public String grouped(long units) {
        boolean negative = units < 0;
        BigDecimal value = Money.decimal(Math.abs(units), settings.decimals());
        String text = value.toPlainString();
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot + 1);
        StringBuilder out = new StringBuilder(text.length() + 8);
        if (negative) {
            out.append('-');
        }
        int lead = whole.length() % 3 == 0 ? 3 : whole.length() % 3;
        out.append(whole, 0, lead);
        for (int i = lead; i < whole.length(); i += 3) {
            out.append(settings.thousandsSeparator()).append(whole, i, i + 3);
        }
        if (!fraction.isEmpty()) {
            out.append(settings.decimalSeparator()).append(fraction);
        }
        return out.toString();
    }

    /** Short, for scoreboards: {@code 950}, {@code 1.2k}, {@code 3.4M}. */
    public String shortForm(long units) {
        boolean negative = units < 0;
        long whole = Math.abs(units) / Money.ONE;
        if (whole < 1000) {
            return (negative ? "-" : "") + whole;
        }
        int tier = -1;
        double value = whole;
        while (value >= 1000 && tier < suffixes.length - 1) {
            value /= 1000;
            tier++;
        }
        // One decimal, rounded down, so 1,999 is 1.9k and never shows as 2k.
        long tenths = (long) Math.floor(value * 10);
        String number = tenths % 10 == 0 ? Long.toString(tenths / 10) : (tenths / 10) + settings.decimalSeparator() + (tenths % 10);
        return (negative ? "-" : "") + number + suffixes[tier];
    }

    /**
     * An amount as a player types it: {@code 250}, {@code 1,000}, {@code 12.5}, {@code 1.5k},
     * {@code 2m}. The thousands separator is ignored; the decimal separator, or a dot, marks
     * the decimals. A suffix multiplies by a thousand, a million, a billion or a trillion.
     *
     * @return the amount in units, at least zero
     * @throws IllegalArgumentException if it isn't an amount, or has more decimals than the currency
     */
    public long parse(String typed) {
        String text = typed.strip();
        if (!settings.thousandsSeparator().isEmpty() && !settings.thousandsSeparator().equals(settings.decimalSeparator())) {
            text = text.replace(settings.thousandsSeparator(), "");
        }
        text = text.replace(settings.decimalSeparator(), ".");
        BigDecimal multiplier = BigDecimal.ONE;
        if (!text.isEmpty()) {
            char last = Character.toLowerCase(text.charAt(text.length() - 1));
            int power = switch (last) {
                case 'k' -> 3;
                case 'm' -> 6;
                case 'b' -> 9;
                case 't' -> 12;
                default -> 0;
            };
            if (power > 0) {
                multiplier = BigDecimal.TEN.pow(power);
                text = text.substring(0, text.length() - 1);
            }
        }
        if (!text.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
            throw new IllegalArgumentException("not an amount");
        }
        BigDecimal value = new BigDecimal(text).multiply(multiplier);
        if (value.stripTrailingZeros().scale() > settings.decimals()) {
            throw new IllegalArgumentException("too many decimals");
        }
        long units = Money.units(value, settings.decimals());
        if (units < 0) {
            throw new IllegalArgumentException("too large");
        }
        return units;
    }

    /** The currency's id, {@code default} in 1.0. */
    public static String currencyId() {
        return "default";
    }
}
