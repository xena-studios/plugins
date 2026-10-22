package co.xenastudios.zchat.config;

import java.util.List;

/** A command that can be turned off, renamed and given its own permission. */
public interface Toggle {

    boolean enabled();

    List<String> aliases();

    String permission();
}
