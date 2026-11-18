package co.xenastudios.xteleport.api;

/**
 * How a teleport ended.
 *
 * @since 1.0.0
 */
public enum TeleportResult {
    /**
     * The player arrived.
     *
     * @since 1.0.0
     */
    TELEPORTED,
    /**
     * The player moved during the warmup, or took damage, or started another teleport.
     *
     * @since 1.0.0
     */
    CANCELLED,
    /**
     * A plugin cancelled {@link co.xenastudios.xteleport.api.event.PreTeleportEvent}.
     *
     * @since 1.0.0
     */
    BLOCKED,
    /**
     * The cooldown hadn't run out.
     *
     * @since 1.0.0
     */
    ON_COOLDOWN,
    /**
     * The player couldn't pay the cost, or the economy isn't working.
     *
     * @since 1.0.0
     */
    CANT_AFFORD,
    /**
     * The destination was unsafe (lava, inside blocks, or over the void), or its world
     * isn't loaded.
     *
     * @since 1.0.0
     */
    UNSAFE,
    /**
     * The server refused the teleport, or the player left. Anything charged was refunded.
     *
     * @since 1.0.0
     */
    FAILED
}
