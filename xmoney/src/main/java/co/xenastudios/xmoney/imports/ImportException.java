package co.xenastudios.xmoney.imports;

/** There's nothing to import, or it couldn't be read. The message says why, for the sender. */
public final class ImportException extends RuntimeException {

    private final boolean missing;

    public ImportException(String message, boolean missing) {
        super(message, null, false, false);
        this.missing = missing;
    }

    /** Whether there was simply nothing there, rather than something unreadable. */
    public boolean missing() {
        return missing;
    }
}
