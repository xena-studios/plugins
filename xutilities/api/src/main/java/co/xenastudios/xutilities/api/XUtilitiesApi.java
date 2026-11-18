package co.xenastudios.xutilities.api;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * xUtilities' developer API: the spot {@code /back} returns a player to, and which of
 * xUtilities' commands are on.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered
 * while xUtilities is enabled. Every method may be called from any thread, answers from
 * memory and returns immediately.
 *
 * <p>The {@code /back} methods work only while {@code /back} is turned on in xUtilities'
 * config ({@link #isBackEnabled()}). While it's off, nothing is remembered: getting a
 * spot returns empty and setting one does nothing.
 *
 * @since 2.1.0
 */
public interface XUtilitiesApi {

    /**
     * xUtilities' API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xUtilities isn't installed and enabled
     * @since 2.1.0
     */
    static XUtilitiesApi get() {
        XUtilitiesApi api = Bukkit.getServicesManager().load(XUtilitiesApi.class);
        if (api == null) {
            throw new IllegalStateException("xUtilities isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * Whether {@code /back} is turned on, so xUtilities remembers where players were.
     * Any thread, answers from memory.
     *
     * @return true while {@code /back} is on
     * @since 2.1.0
     */
    boolean isBackEnabled();

    /**
     * Where {@code /back} would take a player: where they were before their last
     * teleport by a command or plugin, or where they died. Any thread, answers from
     * memory.
     *
     * @param player an online player
     * @return a copy of the spot, or empty if there's none or {@code /back} is off
     * @since 2.1.0
     */
    Optional<Location> getBackLocation(Player player);

    /**
     * Sets where {@code /back} takes a player, for a teleport xUtilities doesn't see as
     * one to go back from (one without the {@code COMMAND}, {@code PLUGIN} or
     * {@code UNKNOWN} cause, or a move that isn't a teleport). Doesn't fire
     * {@link co.xenastudios.xutilities.api.event.BackRecordEvent}: the caller decided.
     * Any thread, answers from memory. The spot is forgotten when the player leaves.
     *
     * @param player an online player
     * @param location the spot, in a loaded world; it's copied
     * @return true if it was set; false if {@code /back} is off or the player isn't online
     * @since 2.1.0
     */
    boolean setBackLocation(Player player, Location location);

    /**
     * Forgets where {@code /back} would take a player, such as when they join a minigame
     * they mustn't return to. Any thread, answers from memory.
     *
     * @param player a player
     * @since 2.1.0
     */
    void clearBackLocation(Player player);

    /**
     * Whether one of xUtilities' commands, including the link commands an owner defines,
     * is turned on under this name or alias. Lets another plugin avoid a clashing name,
     * or point players at the right command. Any thread, answers from memory.
     *
     * @param label a command name or alias, with or without the leading {@code /}; case
     *     doesn't matter
     * @return true if xUtilities has a command turned on by that name or alias
     * @since 2.1.0
     */
    boolean isCommandEnabled(String label);
}
