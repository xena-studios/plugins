package co.xenastudios.xbans.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * An account that has joined from one of the same IPs as another.
 *
 * @param id the account's uuid
 * @param name its last known name
 * @param lastSeen when it last joined from a shared IP
 * @since 1.0.0
 */
public record Alt(@NotNull UUID id, @NotNull String name, @NotNull Instant lastSeen) {}
