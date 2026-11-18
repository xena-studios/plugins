package co.xenastudios.xplots.model;

import co.xenastudios.core.text.Durations;
import co.xenastudios.xplots.config.XPConfig.PlotSettings;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A field's own settings, set with {@code /xplots field set}, over the config's. */
public final class Settings {

    public static final List<String> KEYS = List.of(
        "price", "period", "max-prepaid", "starting-slots", "max-slots", "slot-price", "reset", "abandon-refund",
        "sign-position", "sign-rotation");

    /** The settings that move a field's signs when they change. */
    public static final List<String> SIGN_KEYS = List.of("sign-position", "sign-rotation");

    private Settings() {}

    /**
     * Checks a value for a setting and returns it as it's stored.
     *
     * @throws IllegalArgumentException saying what's wrong, for the sender
     */
    public static String check(String key, String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        switch (key) {
            case "price", "slot-price" -> {
                double number = number(v);
                if (number < 0 || !Double.isFinite(number)) {
                    throw new IllegalArgumentException("must be 0 or more");
                }
                return strip(number);
            }
            case "period", "max-prepaid" -> {
                Duration duration = Durations.parse(v);
                if (duration.toMinutes() < 1) {
                    throw new IllegalArgumentException("must be at least a minute");
                }
                return Durations.format(duration).replace(" ", "");
            }
            case "starting-slots", "max-slots" -> {
                return Integer.toString(whole(v, 0, 45));
            }
            case "abandon-refund" -> {
                return Integer.toString(whole(v.replace("%", ""), 0, 100));
            }
            case "sign-position" -> {
                return oneOf(SignPlacement.Position.class, v);
            }
            case "sign-rotation" -> {
                return oneOf(SignPlacement.Rotation.class, v);
            }
            case "reset" -> {
                if (!v.equals("true") && !v.equals("false")) {
                    throw new IllegalArgumentException("must be true or false");
                }
                return v;
            }
            default -> throw new IllegalArgumentException("isn't a setting. Use one of: " + String.join(", ", KEYS));
        }
    }

    /** The defaults with a field's own settings on top. Values were checked when set; a bad one is skipped. */
    public static PlotSettings apply(PlotSettings base, Map<String, String> overrides) {
        double price = base.price();
        Duration period = base.period();
        Duration maxPrepaid = base.maxPrepaid();
        int startingSlots = base.startingSlots();
        int maxSlots = base.maxSlots();
        double slotPrice = base.slotPrice();
        boolean reset = base.reset();
        int refund = base.abandonRefund();
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            try {
                String v = check(entry.getKey(), entry.getValue());
                switch (entry.getKey()) {
                    case "price" -> price = Double.parseDouble(v);
                    case "period" -> period = Durations.parse(v);
                    case "max-prepaid" -> maxPrepaid = Durations.parse(v);
                    case "starting-slots" -> startingSlots = Integer.parseInt(v);
                    case "max-slots" -> maxSlots = Integer.parseInt(v);
                    case "slot-price" -> slotPrice = Double.parseDouble(v);
                    case "reset" -> reset = Boolean.parseBoolean(v);
                    case "abandon-refund" -> refund = Integer.parseInt(v);
                    default -> { }
                }
            } catch (IllegalArgumentException ignored) {
                // Checked when it was set; one from a hand-edited database is skipped.
            }
        }
        return new PlotSettings(price, period, maxPrepaid, startingSlots, Math.max(startingSlots, maxSlots), slotPrice, reset, refund);
    }

    /** {@code price=128;reset=false}, as stored. */
    public static String encode(Map<String, String> overrides) {
        StringBuilder out = new StringBuilder();
        overrides.forEach((key, value) -> {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(key).append('=').append(value);
        });
        return out.toString();
    }

    public static Map<String, String> decode(String stored) {
        Map<String, String> overrides = new LinkedHashMap<>();
        if (stored != null) {
            for (String pair : stored.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    overrides.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return overrides;
    }

    private static <E extends Enum<E>> String oneOf(Class<E> type, String v) {
        E value = SignPlacement.parse(type, v, null);
        if (value == null) {
            throw new IllegalArgumentException("must be one of " + String.join(", ",
                java.util.Arrays.stream(type.getEnumConstants()).map(SignPlacement::name).toList()));
        }
        return SignPlacement.name(value);
    }

    /** The values a setting takes, for tab completion; empty for free-form ones. */
    public static List<String> values(String key) {
        return switch (key) {
            case "sign-position" -> java.util.Arrays.stream(SignPlacement.Position.values()).map(SignPlacement::name).toList();
            case "sign-rotation" -> java.util.Arrays.stream(SignPlacement.Rotation.values()).map(SignPlacement::name).toList();
            case "reset" -> List.of("true", "false");
            default -> List.of();
        };
    }

    private static double number(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("must be a number");
        }
    }

    private static int whole(String v, int min, int max) {
        try {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // Falls through.
        }
        throw new IllegalArgumentException("must be a whole number from " + min + " to " + max);
    }

    /** 128.0 as 128, 2.5 as 2.5. */
    public static String strip(double number) {
        return number == Math.rint(number) && Math.abs(number) < 1e15 ? Long.toString((long) number)
            : java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }
}
