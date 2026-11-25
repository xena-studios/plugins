package co.xenastudios.core.velocity

/**
 * Where the platform's listeners run among a Velocity event's, as Velocity 3.3's numeric
 * priorities (higher runs first), which replaced `PostOrder` in its API: the same values
 * `PostOrder.FIRST`, `LATE` and `LAST` stand for.
 */
internal object Priority {
    const val FIRST: Short = Short.MAX_VALUE
    const val LATE: Short = -16384
    const val LAST: Short = Short.MIN_VALUE
}
