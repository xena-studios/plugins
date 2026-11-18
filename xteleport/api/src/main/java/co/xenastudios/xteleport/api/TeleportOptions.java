package co.xenastudios.xteleport.api;

/**
 * How {@link XTeleportApi#teleport} behaves. Start from {@link #defaults()} and change
 * what you need: {@code TeleportOptions.defaults().cooldown("plothome", 30).cost(5)}.
 *
 * @param warmupSeconds seconds to stand still first; -1 uses xTeleport's own warmup
 *     setting, 0 teleports at once. {@code xteleport.warmup.bypass} skips it.
 * @param cooldownKey the cooldown to check and start, such as {@code "plothome"}; blank
 *     for none. A cooldown still running on this key refuses the teleport, whatever
 *     {@code cooldownSeconds} is. A key xTeleport uses itself ({@code home}, {@code warp},
 *     {@code spawn}, {@code tpa}, {@code tpahere}, {@code rtp}) shares that command's
 *     cooldown, both ways. {@code xteleport.cooldown.bypass} skips the check.
 * @param cooldownSeconds how long a new cooldown on the key lasts, started when the
 *     teleport arrives; 0 checks the key without starting one
 * @param cost what the teleport costs through Vault, charged after the warmup and
 *     refunded if it fails; 0 for nothing. {@code xteleport.cost.bypass} skips it.
 * @param checkSafe whether to refuse a destination in lava, inside blocks, or over the void
 * @since 1.0.0
 */
public record TeleportOptions(int warmupSeconds, String cooldownKey, int cooldownSeconds, double cost, boolean checkSafe) {

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if a number is negative, other than a warmup of -1
     * @since 1.0.0
     */
    public TeleportOptions {
        if (warmupSeconds < -1 || cooldownSeconds < 0 || cost < 0 || Double.isNaN(cost)) {
            throw new IllegalArgumentException("Negative warmup, cooldown or cost");
        }
        cooldownKey = cooldownKey == null ? "" : cooldownKey;
    }

    /**
     * xTeleport's own warmup, no cooldown, no cost, and the safety check.
     *
     * @return the defaults
     * @since 1.0.0
     */
    public static TeleportOptions defaults() {
        return new TeleportOptions(-1, "", 0, 0, true);
    }

    /**
     * These options with another warmup.
     *
     * @param seconds seconds to stand still; -1 for xTeleport's own setting, 0 for none
     * @return the new options
     * @since 1.0.0
     */
    public TeleportOptions warmup(int seconds) {
        return new TeleportOptions(seconds, cooldownKey, cooldownSeconds, cost, checkSafe);
    }

    /**
     * These options with a cooldown.
     *
     * @param key the cooldown's key, checked before teleporting
     * @param seconds how long it lasts once the teleport arrives; 0 only checks it
     * @return the new options
     * @since 1.0.0
     */
    public TeleportOptions cooldown(String key, int seconds) {
        return new TeleportOptions(warmupSeconds, key, seconds, cost, checkSafe);
    }

    /**
     * These options with a cost.
     *
     * @param amount what it costs through Vault
     * @return the new options
     * @since 1.0.0
     */
    public TeleportOptions cost(double amount) {
        return new TeleportOptions(warmupSeconds, cooldownKey, cooldownSeconds, amount, checkSafe);
    }

    /**
     * These options with or without the safety check.
     *
     * @param check whether to refuse an unsafe destination
     * @return the new options
     * @since 1.0.0
     */
    public TeleportOptions checkSafe(boolean check) {
        return new TeleportOptions(warmupSeconds, cooldownKey, cooldownSeconds, cost, check);
    }
}
