package co.xenastudios.xmoney.api

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [Amount] and Java's `BigDecimal`, for plugins that worked with xMoney 1.x's `BigDecimal`s:
 * `Amounts.of(price, Rounding.UP)` before a withdrawal, `Amounts.toBigDecimal(balance)` after.
 *
 * @since 2.0.0
 */
public object Amounts {
    /**
     * [value] as an amount, rounded to four places as [rounding] says, or `null` if it's past
     * [Amount.MAX_WHOLE] (or needs rounding with [Rounding.EXACT]).
     */
    @JvmStatic
    public fun of(value: BigDecimal, rounding: Rounding): Amount? {
        val mode = when (rounding) {
            Rounding.EXACT -> RoundingMode.UNNECESSARY
            Rounding.DOWN -> RoundingMode.DOWN
            Rounding.UP -> RoundingMode.UP
            Rounding.HALF_UP -> RoundingMode.HALF_UP
        }
        if (value.abs() > BigDecimal.valueOf(Amount.MAX_WHOLE)) return null
        val scaled = try {
            value.setScale(Amount.SCALE, mode)
        } catch (e: ArithmeticException) {
            return null
        }
        return Amount.ofUnits(scaled.unscaledValue().toLong())
    }

    /** [amount] as a `BigDecimal` with every place it has and at least [decimals]: `12.50`, `0.995`. */
    @JvmStatic
    @JvmOverloads
    public fun toBigDecimal(amount: Amount, decimals: Int = 0): BigDecimal {
        val value = BigDecimal.valueOf(amount.units, Amount.SCALE).stripTrailingZeros()
        return value.setScale(maxOf(decimals, value.scale(), 0), RoundingMode.UNNECESSARY)
    }
}
