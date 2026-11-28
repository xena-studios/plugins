package co.xenastudios.xlimits

import co.xenastudios.xlimits.config.Profile
import co.xenastudios.xlimits.config.Trigger
import kotlin.concurrent.Volatile

/** What a load profile's condition reads, taken when it's checked. `NaN` where the server can't say. */
internal class Readings(val players: Int, val tps: Double, val mspt: Double)

/**
 * One load profile switch: the server's, or on Folia a region's (kept with each chunk's counts).
 * Checked at most once a second, as a limit is checked; it needs no timer.
 *
 * Hysteresis: the strictest profile whose `on` holds turns on at once; the one that's on turns
 * off only once its `off` holds and it's been on for the minimum time, and then the strictest
 * one below it that hasn't reached its own `off` takes over, or none.
 */
internal class ProfileState {
    /** The index of the profile that's on, `-1` for none. */
    @Volatile
    var active: Int = -1
        private set

    /** When it turned on, in the platform's elapsed milliseconds. */
    var since: Long = 0
        private set

    // When it was last checked.
    private var checkedAt: Long = Long.MIN_VALUE / 2

    /** Whether it's time to check again: a second since the last. */
    fun due(now: Long): Boolean = now - checkedAt >= CHECK_EVERY

    /**
     * Checks [profiles] against [readings] at [now], keeping to [minimum] milliseconds on; returns
     * the index switched from if it changed, or `null`. Call only when [due].
     */
    fun check(profiles: List<Rules.NamedProfile>, readings: Readings, now: Long, minimum: Long): Int? {
        checkedAt = now
        if (active >= profiles.size) active = -1
        var candidate = -1
        for (i in profiles.indices.reversed()) {
            if (on(profiles[i].profile, readings)) {
                candidate = i
                break
            }
        }
        val was = active
        when {
            candidate > active -> switch(candidate, now)
            active >= 0 && off(profiles[active].profile, readings) && now - since >= minimum -> {
                // Down to the strictest one below that hasn't reached its own off: its hysteresis holds too.
                var to = -1
                for (i in active - 1 downTo 0) {
                    if (!off(profiles[i].profile, readings)) {
                        to = i
                        break
                    }
                }
                switch(to, now)
            }
        }
        return if (active != was) was else null
    }

    /** Back to no profile: on a reload, whose profiles may be others. */
    fun reset() {
        active = -1
        checkedAt = Long.MIN_VALUE / 2
    }

    private fun switch(to: Int, now: Long) {
        active = to
        since = now
    }

    companion object {
        const val CHECK_EVERY: Long = 1_000

        private fun value(profile: Profile, readings: Readings): Double = when (profile.trigger) {
            Trigger.PLAYERS_ONLINE_ABOVE -> readings.players.toDouble()
            Trigger.TPS_BELOW -> readings.tps
            Trigger.MSPT_ABOVE -> readings.mspt
        }

        /** Whether [profile]'s condition holds: never with no reading. */
        fun on(profile: Profile, readings: Readings): Boolean {
            val v = value(profile, readings)
            if (v.isNaN()) return false
            return if (profile.trigger == Trigger.TPS_BELOW) v < profile.on else v > profile.on
        }

        /** Whether [profile] may turn off: its off value is reached (or its reading is gone). */
        fun off(profile: Profile, readings: Readings): Boolean {
            val v = value(profile, readings)
            if (v.isNaN()) return true
            return if (profile.trigger == Trigger.TPS_BELOW) {
                v >= maxOf(profile.off, profile.on)
            } else {
                v <= minOf(profile.off, profile.on)
            }
        }
    }
}
