package co.xenastudios.core.db;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.Range;

/**
 * The {@code network} section of a plugin that can run on several servers sharing one
 * MySQL or MariaDB database. With it on, each server checks the plugin's
 * {@link ChangeFeed} every few seconds for changes the others made.
 */
public record NetworkSettings(
    @Comment({
        "On when several servers share this plugin's MySQL or MariaDB database, so a change",
        "made on one (a ban, a payment) reaches the others within a few seconds.",
        "Leave it off for a single server: it does nothing on SQLite."})
    boolean enabled,
    @Comment("Seconds between checks for the other servers' changes.")
    @Range(min = 1, max = 60) int checkEvery) {

    public static final NetworkSettings DEFAULTS = new NetworkSettings(false, 3);
}
