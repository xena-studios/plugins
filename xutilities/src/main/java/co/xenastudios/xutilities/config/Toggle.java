package co.xenastudios.xutilities.config;

import java.util.List;

/** The settings every command has. */
public interface Toggle {

    /** False removes the command. */
    boolean enabled();

    List<String> aliases();

    /** Blank means anyone may use it. */
    String permission();
}
