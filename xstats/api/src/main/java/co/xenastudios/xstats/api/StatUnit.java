package co.xenastudios.xstats.api;

/**
 * What a stat's numbers measure, so they can be shown the right way. Values are always
 * whole numbers in the unit named here.
 *
 * @since 1.0.0
 */
public enum StatUnit {

    /**
     * Things counted: kills, blocks, votes. A stat with {@link StatInfo#decimals()} above 0
     * holds that many decimal places, so 1234 with 2 decimals is 12.34.
     *
     * @since 1.0.0
     */
    COUNT,

    /**
     * A distance, in centimetres, as Minecraft counts it.
     *
     * @since 1.0.0
     */
    DISTANCE,

    /**
     * A length of time, in seconds.
     *
     * @since 1.0.0
     */
    TIME,

    /**
     * Damage, in tenths of a health point, as Minecraft counts it: 20 is one heart.
     *
     * @since 1.0.0
     */
    DAMAGE
}
