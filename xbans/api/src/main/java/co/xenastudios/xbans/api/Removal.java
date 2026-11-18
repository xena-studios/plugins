package co.xenastudios.xbans.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who revoked a punishment, when and why: an unban, unmute or unwarn. Revoking never
 * deletes the punishment; it's kept in the history with this beside it.
 *
 * @param staff who revoked it, or null for the console or a plugin
 * @param staffName their name as shown in the history, such as {@code "Console"}
 * @param at when it was revoked
 * @param reason why, or an empty string
 * @since 1.0.0
 */
public record Removal(@Nullable UUID staff, @NotNull String staffName, @NotNull Instant at, @NotNull String reason) {}
