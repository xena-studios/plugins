package co.xenastudios.xrewind.api;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Who made a change you log: a player, or a source such as {@code #xplots}, which has no
 * uuid. Lookups find it by name, {@code u:Steve} or {@code u:#xplots}.
 *
 * @param uuid the player's uuid, or null for a source
 * @param name the player's name, or the source's, starting with {@code #}
 * @since 1.1.0
 */
public record Actor(@Nullable UUID uuid, @NotNull String name) {

    /**
     * Checks the name: a source's starts with {@code #}, a player's doesn't.
     *
     * @param uuid the player's uuid, or null for a source
     * @param name the player's name, or the source's, starting with {@code #}
     * @since 1.1.0
     */
    public Actor {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("An actor's name is 1 to 64 characters");
        }
        if ((uuid == null) != name.startsWith("#")) {
            throw new IllegalArgumentException(uuid == null
                ? "A source's name starts with #, such as #xplots" : "A player's name doesn't start with #");
        }
    }

    /**
     * A player.
     *
     * @param player the player
     * @return the actor
     * @throws IllegalArgumentException if the player has no name (never joined)
     * @since 1.1.0
     */
    public static @NotNull Actor player(@NotNull OfflinePlayer player) {
        String name = player.getName();
        if (name == null) {
            throw new IllegalArgumentException("That player has never joined, so has no name");
        }
        return new Actor(player.getUniqueId(), name);
    }

    /**
     * A source that isn't a player, such as your plugin.
     *
     * @param name its name, with or without the leading {@code #}: {@code xplots} and
     *     {@code #xplots} are the same
     * @return the actor
     * @since 1.1.0
     */
    public static @NotNull Actor source(@NotNull String name) {
        return new Actor(null, name.startsWith("#") ? name : "#" + name);
    }

    /**
     * Whether it's a player rather than a source.
     *
     * @return true for a player
     * @since 1.1.0
     */
    public boolean isPlayer() {
        return uuid != null;
    }
}
