package co.xenastudios.xvote.receiver;

/**
 * A vote that couldn't be read or wasn't genuine. {@link #cause} is the error's short
 * name a Votifier v2 reply carries, as NuVotifier names it, so a list shows owners the
 * same error either way.
 */
final class ProtocolException extends Exception {

    private final String cause;
    private final String logKey;

    /**
     * @param cause the short name a v2 reply carries
     * @param logKey errors with the same key are logged once, so one list's bad token doesn't flood the console
     */
    ProtocolException(String cause, String message, String logKey) {
        super(message, null, false, false);
        this.cause = cause;
        this.logKey = logKey;
    }

    String causeName() {
        return cause;
    }

    String logKey() {
        return logKey;
    }
}
