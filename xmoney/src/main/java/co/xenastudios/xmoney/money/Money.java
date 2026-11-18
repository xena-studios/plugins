package co.xenastudios.xmoney.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amounts as whole numbers: every balance and change is a {@code long} of ten-thousandths
 * ("units"), so adding and taking away never drifts the way floating point does. The
 * scale is fixed, whatever the config's decimals, so raising the decimals later needs no
 * change to what's stored.
 *
 * <p>Amounts from outside (Vault's doubles, typed numbers) are rounded half up to the
 * currency's decimals on the way in; the ledger records what was actually moved.
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

    /** The units as a double, for Vault 1, which only speaks doubles. */
    public static double toDouble(long units) {
        return units / (double) ONE;
    }

    /**
     * A positive amount in units, rounded half up to {@code decimals} places, or -1 if it isn't
     * one: null, NaN, infinite, negative, zero once rounded, or past {@link #MAX_WHOLE}.
     */
    public static long positive(BigDecimal amount, int decimals) {
        long units = units(amount, decimals);
        return units > 0 ? units : -1;
    }

    /** {@link #positive(BigDecimal, int)} for a double, as Vault 1 hands them over. */
    public static long positive(double amount, int decimals) {
        return Double.isFinite(amount) ? positive(BigDecimal.valueOf(amount), decimals) : -1;
    }

    /** An amount of zero or more in units, rounded half up to {@code decimals}, or -1 if it isn't one. */
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
