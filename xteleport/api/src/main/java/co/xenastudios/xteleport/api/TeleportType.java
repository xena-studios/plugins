package co.xenastudios.xteleport.api;

/**
 * What kind of teleport it is, for events.
 *
 * @since 1.0.0
 */
public enum TeleportType {
    /**
     * {@code /home}.
     *
     * @since 1.0.0
     */
    HOME,
    /**
     * {@code /warp}.
     *
     * @since 1.0.0
     */
    WARP,
    /**
     * {@code /spawn}.
     *
     * @since 1.0.0
     */
    SPAWN,
    /**
     * An accepted {@code /tpa}: the player who asked goes to the one who accepted.
     *
     * @since 1.0.0
     */
    TPA,
    /**
     * An accepted {@code /tpahere}: the player who accepted goes to the one who asked.
     *
     * @since 1.0.0
     */
    TPAHERE,
    /**
     * {@code /rtp}.
     *
     * @since 1.0.0
     */
    RTP,
    /**
     * Another plugin, through {@link XTeleportApi#teleport}.
     *
     * @since 1.0.0
     */
    PLUGIN
}
