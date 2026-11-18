package co.xenastudios.xmoney.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Amounts as whole numbers: every balance and change is a {@code long} of ten-thousandths
 * ("units"), so adding and taking away never drifts the way floating point does. The
 * scale is fixed, whatever the config's decimals, so raising the decimals later needs no
 * change to what's stored.
 *
 * <p>Amounts from other plugins (Vault, the API) keep all four places, whatever the
 * currency's decimals, so splitting a payment can't add up to more or less than paying it
 * whole. Past four places, what's paid in is rounded down ({@link #incoming}) and what's
 * taken is rounded up ({@link #outgoing}), so rounding never makes money. The currency's
 * decimals are only for showing amounts and for amounts typed into commands
 * ({@link #units}). The ledger records what was actually moved.
 */
public final class Money {

    /** Decimal places the units hold. */
    public static final int SCALE = 4;
    /** Units in one whole coin. */
    public static final long ONE = 10_000;
    /** The largest balance the config may allow: 100 trillion, well inside a {@code long} of units. */
    public static final long MAX_WHOLE = 100_000_000_000_000L;
    /** {@link #MAX_WHOLE} in units. Sums of two balances still fit in a {@code long}. */
    public static final long MAX_UNITS = MAX_WHOLE * ONE;

    private Money() {}

    /** The units as a decimal with {@link #SCALE} places. */
    public static BigDecimal decimal(long units) {
        return BigDecimal.valueOf(units, SCALE);
    }

    /** The units as a decimal with the currency's places, for showing or handing out. */
    public static BigDecimal decimal(long units, int decimals) {
        return BigDecimal.valueOf(units, SCALE).setScale(decimals, RoundingMode.DOWN);
    }

    /**
     * The units as a decimal with every place they have, and at least the currency's: 12.5
     * with 2 decimals is 12.50, and 0.995 stays 0.995. For other plugins, which compute with it.
     */
    public static BigDecimal exact(long units, int decimals) {
        BigDecimal value = BigDecimal.valueOf(units, SCALE).stripTrailingZeros();
        return value.setScale(Math.max(decimals, Math.max(0, value.scale())), RoundingMode.UNNECESSARY);
    }

    /**
     * An amount paid in by another plugin, or a balance it sets, in units: rounded down to
     * {@link #SCALE} places, or -1 if it isn't zero or more (null, negative, or past
     * {@link #MAX_WHOLE}).
     */
    public static long incoming(BigDecimal amount) {
        return scaled(amount, RoundingMode.DOWN);
    }

    /** An amount another plugin takes, or checks for, in units: rounded up to {@link #SCALE} places, or -1. */
    public static long outgoing(BigDecimal amount) {
        return scaled(amount, RoundingMode.UP);
    }

    /** {@link #incoming(BigDecimal)} for a Vault 1 double. */
    public static long incoming(double amount) {
        return Double.isFinite(amount) ? incoming(of(amount)) : -1;
    }

    /** {@link #outgoing(BigDecimal)} for a Vault 1 double. */
    public static long outgoing(double amount) {
        return Double.isFinite(amount) ? outgoing(of(amount)) : -1;
    }

    /**
     * A double as the decimal it stands for: its first 15 significant digits, which a double
     * always holds exactly. What's past them is the noise of binary fractions (0.1 + 0.2 is
     * 0.30000000000000004), which would otherwise round a withdrawal up a unit.
     *
     * <p>That's only done where the 15th digit is finer than a unit, so it clears noise of less
     * than a twentieth of a unit and never rounds an amount by a unit or more. From ten
     * billion up, 15 digits are coarser than units, and the double's own digits are kept, so
     * {@link #incoming} rounds them down and {@link #outgoing} up as for any other amount
     * (rounding there to 15 digits, half even, paid 123456789012.34567 in as
     * 123456789012.346).
     */
    static BigDecimal of(double amount) {
        BigDecimal exact = new BigDecimal(Double.toString(amount));
        int wholeDigits = exact.precision() - exact.scale();
        if (NOISE_DIGITS - wholeDigits <= SCALE) {
            return exact;
        }
        return exact.round(new MathContext(NOISE_DIGITS, RoundingMode.HALF_EVEN));
    }

    /** Significant digits a double always holds exactly. */
    private static final int NOISE_DIGITS = 15;

    private static long scaled(BigDecimal amount, RoundingMode rounding) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(BigDecimal.valueOf(MAX_WHOLE)) > 0) {
            return -1;
        }
        return amount.setScale(SCALE, rounding).movePointRight(SCALE).longValueExact();
    }

    /** The units as a double, for Vault 1, which only speaks doubles. */
    public static double toDouble(long units) {
        return units / (double) ONE;
    }

    /**
     * An amount typed into a command, or set in the config, in units: rounded half up to the
     * currency's {@code decimals}, or -1 if it isn't zero or more.
     */
    public static long units(BigDecimal amount, int decimals) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(BigDecimal.valueOf(MAX_WHOLE)) > 0) {
            return -1;
        }
        return amount.setScale(decimals, RoundingMode.HALF_UP).movePointRight(SCALE).longValueExact();
    }

    /** {@link #units(BigDecimal, int)} for a double. */
    public static long units(double amount, int decimals) {
        return Double.isFinite(amount) ? units(BigDecimal.valueOf(amount), decimals) : -1;
    }
}
