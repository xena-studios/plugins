package co.xenastudios.core.common.platform

/**
 * The plugin's lines in the server console, through the platform's own logger. In English:
 * the console's language is the server owner's business, and translations are for players.
 */
public interface Log {
    /** Something worth knowing, such as what started. */
    public fun info(message: String)

    /** Something wrong that the plugin worked around. */
    public fun warn(message: String, cause: Throwable? = null)

    /** Something that failed, such as a module that turned itself off. */
    public fun error(message: String, cause: Throwable? = null)
}
