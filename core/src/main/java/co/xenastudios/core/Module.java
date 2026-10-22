package co.xenastudios.core;

/**
 * A feature that runs only while it's on, such as a listener. Turned on and off as
 * the config changes; a feature that's off costs nothing.
 */
public interface Module {

    default void enable() {}

    default void disable() {}
}
