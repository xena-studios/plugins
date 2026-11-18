package co.xenastudios.xbackup.backup;

/** Checks exclude patterns when the config loads, so a bad one is reported once, not on every backup. */
public final class Excludes {

    private Excludes() {}

    /** @throws IllegalArgumentException with a message for the owner, if the pattern can't be used */
    public static void check(String pattern) {
        String glob = pattern.strip().replace('\\', '/');
        if (glob.isEmpty()) {
            throw new IllegalArgumentException("an empty pattern");
        }
        Selection.glob(glob);
    }
}
