package co.xenastudios.xbans.api;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A punishment to make with {@link XBansApi#punish}. Start with {@link #of} and add what
 * differs from the defaults with the {@code with...} methods, each of which returns a copy.
 *
 * @param type what to do
 * @param target the player to punish
 * @param reason why; shown to the player and in the history
 * @param duration how long it lasts, or null for permanent (ignored for kicks)
 * @param staff who's punishing, or null for the console or a plugin
 * @param staffName their name as shown in the history
 * @param silent whether only staff are told
 * @param ipBased whether it also applies to the player's last known IP (bans and mutes)
 * @param scope {@code "*"} for every server, a server's name for one, or null for the config's default
 * @since 1.0.0
 */
public record PunishRequest(
    @NotNull PunishmentType type,
    @NotNull UUID target,
    @NotNull String reason,
    @Nullable Duration duration,
    @Nullable UUID staff,
    @NotNull String staffName,
    boolean silent,
    boolean ipBased,
    @Nullable String scope) {

    /**
     * Checks the request.
     *
     * @param type what to do
     * @param target the player to punish
     * @param reason why
     * @param duration how long, or null for permanent
     * @param staff who's punishing, or null
     * @param staffName their name
     * @param silent whether only staff are told
     * @param ipBased whether it also applies to the player's IP
     * @param scope the scope, or null for the default
     * @throws IllegalArgumentException if the duration isn't positive
     * @since 1.0.0
     */
    public PunishRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(staffName, "staffName");
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException("A duration must be positive; use null for permanent.");
        }
    }

    /**
     * A permanent, public punishment by the console, on the default scope.
     *
     * @param type what to do
     * @param target the player
     * @param reason why
     * @return the request
     * @since 1.0.0
     */
    public static @NotNull PunishRequest of(@NotNull PunishmentType type, @NotNull UUID target, @NotNull String reason) {
        return new PunishRequest(type, target, reason, null, null, "Console", false, false, null);
    }

    /**
     * This request lasting {@code duration}.
     *
     * @param duration how long, or null for permanent
     * @return a copy
     * @since 1.0.0
     */
    public @NotNull PunishRequest withDuration(@Nullable Duration duration) {
        return new PunishRequest(type, target, reason, duration, staff, staffName, silent, ipBased, scope);
    }

    /**
     * This request made by someone: a player, or a name such as an anti-cheat's.
     *
     * @param staff their uuid, or null if they aren't a player
     * @param staffName their name as shown in the history
     * @return a copy
     * @since 1.0.0
     */
    public @NotNull PunishRequest withStaff(@Nullable UUID staff, @NotNull String staffName) {
        return new PunishRequest(type, target, reason, duration, staff, staffName, silent, ipBased, scope);
    }

    /**
     * This request told only to staff, or to everyone.
     *
     * @param silent true to tell only staff
     * @return a copy
     * @since 1.0.0
     */
    public @NotNull PunishRequest withSilent(boolean silent) {
        return new PunishRequest(type, target, reason, duration, staff, staffName, silent, ipBased, scope);
    }

    /**
     * This request also applying to the player's last known IP.
     *
     * @param ipBased true for an IP ban or mute
     * @return a copy
     * @since 1.0.0
     */
    public @NotNull PunishRequest withIpBased(boolean ipBased) {
        return new PunishRequest(type, target, reason, duration, staff, staffName, silent, ipBased, scope);
    }

    /**
     * This request on a scope.
     *
     * @param scope {@code "*"} for every server, a server's name, or null for the default
     * @return a copy
     * @since 1.0.0
     */
    public @NotNull PunishRequest withScope(@Nullable String scope) {
        return new PunishRequest(type, target, reason, duration, staff, staffName, silent, ipBased, scope);
    }
}
