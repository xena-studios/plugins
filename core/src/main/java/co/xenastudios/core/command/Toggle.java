package co.xenastudios.core.command;

import java.util.List;

/**
 * The settings every switchable command has in a plugin's config: whether it's on,
 * its aliases and its permission. A plugin's settings record implements this, and
 * {@link CommandSpec#named(String, Toggle)} starts a command from it.
 */
public interface Toggle {

    /** False removes the command: it isn't registered at all. */
    boolean enabled();

    List<String> aliases();

    /** Blank means anyone may use it. */
    String permission();
}
