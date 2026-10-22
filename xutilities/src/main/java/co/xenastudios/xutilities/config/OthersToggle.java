package co.xenastudios.xutilities.config;

/** A command with a {@code [player]} form. */
public interface OthersToggle extends Toggle {

    /** Needed to act on another player. Blank means anyone may. */
    String permissionOthers();
}
