package co.xenastudios.m0.loader;

/** The plugin's real code, in Kotlin, loaded once the stdlib is visible. */
public interface Delegate {
    void enable();

    void disable();
}
