package co.xenastudios.xbans.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * xBans' developer API: whether a player is banned or muted, punishing and revoking as a
 * staff command does, and reading history.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered while
 * xBans is enabled.
 *
 * <p>Two kinds of method. The checks ({@link #isBanned}, {@link #getActiveMute} and the
 * rest) may be called from any thread, answer from memory and return immediately: cheap
 * enough for a join or chat path. Everything that touches the database returns a
 * {@link CompletableFuture} that completes off the server's threads, so code that then
 * touches a player or the world must hop back onto the right thread itself.
 *
 * @since 1.0.0
 */
public interface XBansApi {

    /**
     * xBans' API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xBans isn't installed and enabled
     * @since 1.0.0
     */
    static @NotNull XBansApi get() {
        XBansApi api = Bukkit.getServicesManager().load(XBansApi.class);
        if (api == null) {
            throw new IllegalStateException("xBans isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * Whether the player is banned on this server, by account. Any thread, answers from
     * memory, returns immediately.
     *
     * @param player the player's uuid
     * @return true if a ban applies on this server
     * @since 1.0.0
     */
    boolean isBanned(@NotNull UUID player);

    /**
     * Whether the player is muted on this server, by account or by their current IP if
     * they're online. Any thread, answers from memory, returns immediately.
     *
     * @param player the player's uuid
     * @return true if a mute applies on this server
     * @since 1.0.0
     */
    boolean isMuted(@NotNull UUID player);

    /**
     * The ban that applies to the player on this server, by account. Any thread, answers
     * from memory, returns immediately.
     *
     * @param player the player's uuid
     * @return the ban, or empty if they aren't banned here
     * @since 1.0.0
     */
    @NotNull Optional<Punishment> getActiveBan(@NotNull UUID player);

    /**
     * The mute that applies to the player on this server, by account or by their current
     * IP if they're online. Any thread, answers from memory, returns immediately.
     *
     * @param player the player's uuid
     * @return the mute, or empty if they aren't muted here
     * @since 1.0.0
     */
    @NotNull Optional<Punishment> getActiveMute(@NotNull UUID player);

    /**
     * Punishes a player as a staff command would: checked, stored, broadcast, and the
     * player kicked or told. Fires {@link co.xenastudios.xbans.api.event.PunishEvent}
     * first, which can cancel it. None of the staff rules apply: a player with
     * {@code xbans.exempt} or a weight can be punished, for any length. The caller decides
     * who may punish whom. Returns a future that completes off the server's threads.
     *
     * @param request what to do
     * @return the punishment once it's stored; completes exceptionally with
     *     {@link java.util.concurrent.CancellationException} if an event cancelled it, or
     *     {@link IllegalStateException} if it couldn't be made (the message says why)
     * @since 1.0.0
     */
    @NotNull CompletableFuture<Punishment> punish(@NotNull PunishRequest request);

    /**
     * Revokes the player's active ban, mute or newest warning, as {@code /unban},
     * {@code /unmute} or {@code /unwarn} would, whoever made it (weights don't apply).
     * Returns a future that completes off the server's threads.
     *
     * @param player the player's uuid
     * @param type {@link PunishmentType#BAN}, {@link PunishmentType#MUTE} or {@link PunishmentType#WARN}
     * @param staff who's revoking it, or null for the console or a plugin
     * @param staffName their name as shown in the history
     * @param reason why, or an empty string
     * @return the punishment revoked, with its {@link Removal}; empty if there was none to revoke
     * @since 1.0.0
     */
    @NotNull CompletableFuture<Optional<Punishment>> revoke(@NotNull UUID player, @NotNull PunishmentType type,
                                                           @Nullable UUID staff, @NotNull String staffName,
                                                           @NotNull String reason);

    /**
     * A page of a player's punishments, newest first. Returns a future that completes off
     * the server's threads.
     *
     * @param player the player's uuid
     * @param page the page, from 1; past the end gives an empty page
     * @param perPage how many to a page, from 1 to 100
     * @return the page
     * @since 1.0.0
     */
    @NotNull CompletableFuture<HistoryPage> history(@NotNull UUID player, int page, int perPage);

    /**
     * A page of the punishments a staff member made, newest first. Returns a future that
     * completes off the server's threads.
     *
     * @param staff the staff member's uuid, or null for the console
     * @param page the page, from 1; past the end gives an empty page
     * @param perPage how many to a page, from 1 to 100
     * @return the page
     * @since 1.0.0
     */
    @NotNull CompletableFuture<HistoryPage> staffHistory(@Nullable UUID staff, int page, int perPage);

    /**
     * Every other account that has joined from one of the player's IPs, most recent
     * first. Returns a future that completes off the server's threads.
     *
     * @param player the player's uuid
     * @return the accounts, not including the player
     * @since 1.0.0
     */
    @NotNull CompletableFuture<List<Alt>> alts(@NotNull UUID player);
}
