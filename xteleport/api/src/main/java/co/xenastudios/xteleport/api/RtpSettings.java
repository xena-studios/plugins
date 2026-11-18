package co.xenastudios.xteleport.api;

/**
 * Where {@link XTeleportApi#findSafeLocation} looks: a ring around a centre, kept inside
 * the world border.
 *
 * @param centerX the ring's centre, x
 * @param centerZ the ring's centre, z
 * @param minRadius the nearest a spot may be to the centre, in blocks
 * @param maxRadius the farthest, in blocks
 * @param attempts how many spots to try before giving up, from 1 to 50
 * @param onlyGenerated only try chunks that already exist, so nothing is generated
 * @since 1.0.0
 */
public record RtpSettings(int centerX, int centerZ, int minRadius, int maxRadius, int attempts, boolean onlyGenerated) {

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if a radius is negative, the minimum is above the
     *     maximum, or attempts isn't from 1 to 50
     * @since 1.0.0
     */
    public RtpSettings {
        if (minRadius < 0 || maxRadius < minRadius || attempts < 1 || attempts > 50) {
            throw new IllegalArgumentException("Radii must be 0 <= min <= max, and attempts from 1 to 50");
        }
    }
}
