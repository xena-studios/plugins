package co.xenastudios.xbans.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One punishment, as xBans stores it. Punishments are never changed once made: revoking
 * one adds a {@link Removal} beside it.
 *
 * @param id its number, unique in the database, as shown in {@code /history} and used as {@code #id}
 * @param type what it does
 * @param target the punished player, or null for an IP punished without a known account
 * @param targetName the player's name when punished, or a short form of the IP
 * @param ipBased whether it also applies to everyone joining from the player's IP
 * @param reason why
 * @param staff who made it, or null for the console or a plugin
 * @param staffName their name as shown in the history, such as {@code "Console"}
 * @param created when it was made
 * @param expires when it ends, or null if it's permanent (and for kicks)
 * @param scope {@code "*"} for every server, or the one server it applies on
 * @param silent whether only staff were told about it
 * @param template the template it followed, such as {@code "hacking"}, or null
 * @param removal who revoked it, or null if nobody has
 * @since 1.0.0
 */
public record Punishment(
    long id,
    @NotNull PunishmentType type,
    @Nullable UUID target,
    @NotNull String targetName,
    boolean ipBased,
    @NotNull String reason,
    @Nullable UUID staff,
    @NotNull String staffName,
    @NotNull Instant created,
    @Nullable Instant expires,
    @NotNull String scope,
    boolean silent,
    @Nullable String template,
    @Nullable Removal removal) {

    /** The scope of a punishment that applies on every server. */
    public static final String GLOBAL = "*";

    /**
     * Whether it never ends on its own.
     *
     * @return true if it has no expiry
     * @since 1.0.0
     */
    public boolean isPermanent() {
        return expires == null;
    }

    /**
     * Whether it's in force at {@code now}: a ban, mute or warning that hasn't ended or
     * been revoked. A kick never is.
     *
     * @param now the moment to ask about
     * @return true if it's in force
     * @since 1.0.0
     */
    public boolean isActive(@NotNull Instant now) {
        return type.lasts() && removal == null && (expires == null || expires.isAfter(now));
    }

    /**
     * Whether it applies on every server rather than one.
     *
     * @return true for scope {@value #GLOBAL}
     * @since 1.0.0
     */
    public boolean isGlobal() {
        return GLOBAL.equals(scope);
    }

    /**
     * The same punishment, revoked.
     *
     * @param removal who revoked it
     * @return a copy with {@code removal} set
     * @since 1.0.0
     */
    public @NotNull Punishment withRemoval(@NotNull Removal removal) {
        return new Punishment(id, type, target, targetName, ipBased, reason, staff, staffName, created, expires, scope,
            silent, template, removal);
    }
}
