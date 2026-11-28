package co.xenastudios.xplots.model

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.time.Durations
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A field's rent settings: the config's `plots`, with the field's own on top. */
public class PlotSettings(
    public val price: Double,
    public val period: Duration,
    public val maxPrepaid: Duration,
    public val startingSlots: Int,
    public val maxSlots: Int,
    public val slotPrice: Double,
    public val reset: Boolean,
    public val abandonRefund: Int,
    /** 2.0: rent renews on its own as it runs out, from the owner's balance, online or not. */
    public val autoRenew: Boolean,
)

/**
 * A field's own settings, set with `/xplots field set`, over the config's: stored as 1.x stored
 * them (`price=128;reset=false`), so either jar reads the other's. 1.x skips a key it doesn't
 * know (`auto-renew`, 2.0's), as 2.0 does.
 */
public object Settings {
    public val KEYS: List<String> = listOf(
        "price", "period", "max-prepaid", "starting-slots", "max-slots", "slot-price", "reset", "abandon-refund",
        "sign-position", "sign-rotation", "auto-renew",
    )

    /** The settings that move a field's signs when they change. */
    public val SIGN_KEYS: List<String> = listOf("sign-position", "sign-rotation")

    /** The most member slots a plot can have: what the members menu shows. */
    public const val MAX_SLOTS: Int = 45

    /** Checks [value] for [key] and returns it as it's stored, or why it's wrong (`must be 0 or more`). */
    public fun check(key: String, value: String): Outcome<String, String> {
        val v = value.trim().lowercase()
        return when (key) {
            "price", "slot-price" -> {
                val number = v.toDoubleOrNull() ?: return Outcome.Err("must be a number")
                if (number < 0 || !number.isFinite()) Outcome.Err("must be 0 or more") else Outcome.Ok(strip(number))
            }
            "period", "max-prepaid" -> when (val parsed = Durations.parse(v)) {
                is Outcome.Ok -> if (parsed.value < 1.minutes) Outcome.Err("must be at least a minute") else Outcome.Ok(Durations.write(parsed.value))
                is Outcome.Err -> Outcome.Err("must be a time, such as 3d, 12h or 1d12h")
            }
            "starting-slots", "max-slots" -> whole(v, 0, MAX_SLOTS)
            "abandon-refund" -> whole(v.replace("%", ""), 0, 100)
            "sign-position" -> oneOf(SignPlacement.Position.entries, v)
            "sign-rotation" -> oneOf(SignPlacement.Rotation.entries, v)
            "reset", "auto-renew" -> if (v == "true" || v == "false") Outcome.Ok(v) else Outcome.Err("must be true or false")
            else -> Outcome.Err("isn't a setting. Use one of: ${KEYS.joinToString(", ")}")
        }
    }

    /** The defaults with a field's own settings on top. Values were checked when set; a bad one (a hand-edited database) is skipped. */
    public fun apply(base: PlotSettings, overrides: Map<String, String>): PlotSettings {
        var price = base.price
        var period = base.period
        var maxPrepaid = base.maxPrepaid
        var startingSlots = base.startingSlots
        var maxSlots = base.maxSlots
        var slotPrice = base.slotPrice
        var reset = base.reset
        var refund = base.abandonRefund
        var autoRenew = base.autoRenew
        for ((key, raw) in overrides) {
            val v = (check(key, raw) as? Outcome.Ok)?.value ?: continue
            when (key) {
                "price" -> price = v.toDouble()
                "period" -> period = (Durations.parse(v) as Outcome.Ok).value
                "max-prepaid" -> maxPrepaid = (Durations.parse(v) as Outcome.Ok).value
                "starting-slots" -> startingSlots = v.toInt()
                "max-slots" -> maxSlots = v.toInt()
                "slot-price" -> slotPrice = v.toDouble()
                "reset" -> reset = v == "true"
                "abandon-refund" -> refund = v.toInt()
                "auto-renew" -> autoRenew = v == "true"
            }
        }
        return PlotSettings(price, period, maxPrepaid, startingSlots, maxOf(startingSlots, maxSlots), slotPrice, reset, refund, autoRenew)
    }

    /** `price=128;reset=false`, as stored. */
    public fun encode(overrides: Map<String, String>): String = overrides.entries.joinToString(";") { "${it.key}=${it.value}" }

    public fun decode(stored: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (stored == null) return out
        for (pair in stored.split(';')) {
            val eq = pair.indexOf('=')
            if (eq > 0) out[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        return out
    }

    /** The values a setting takes, for completion; empty for free-form ones. */
    public fun values(key: String): List<String> = when (key) {
        "sign-position" -> SignPlacement.Position.entries.map(SignPlacement::name)
        "sign-rotation" -> SignPlacement.Rotation.entries.map(SignPlacement::name)
        "reset", "auto-renew" -> listOf("true", "false")
        else -> emptyList()
    }

    private fun <E : Enum<E>> oneOf(values: List<E>, v: String): Outcome<String, String> {
        val value = SignPlacement.parse(values, v) ?: return Outcome.Err("must be one of ${values.joinToString(", ") { SignPlacement.name(it) }}")
        return Outcome.Ok(SignPlacement.name(value))
    }

    private fun whole(v: String, min: Int, max: Int): Outcome<String, String> {
        val n = v.toIntOrNull()
        return if (n != null && n in min..max) Outcome.Ok(n.toString()) else Outcome.Err("must be a whole number from $min to $max")
    }

    /** 128.0 as `128`, 2.5 as `2.5`: as 1.x stored numbers. */
    public fun strip(number: Double): String {
        if (number == kotlin.math.floor(number) && kotlin.math.abs(number) < 1e15) return number.toLong().toString()
        var text = number.toString()
        if ('E' in text || 'e' in text) return text
        if ('.' in text) text = text.trimEnd('0').trimEnd('.')
        return text
    }
}
