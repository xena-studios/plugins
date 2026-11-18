package co.xenastudios.xvote.receiver;

/**
 * A vote that couldn't be read or wasn't genuine. {@link #cause} is the error's short
 * name a Votifier v2 reply carries, as NuVotifier names it, so a list shows owners the
 * same error either way.
 *
 * <p>Two kinds, logged differently. A <b>vote error</b> is what a list set up wrong
 * causes (a wrong token, the wrong public key): logged once per list, from a budget of
 * its own, so the owner sees which list to fix. <b>Noise</b> is what anything else on
 * the internet sends (port scanners, garbage): logged once per kind, without the address
 * or names it carried, so it can't fill the log or crowd out the vote errors.
 */
final class ProtocolException extends Exception {

    private final String cause;
    private final String logKey;
    private final boolean voteError;
    private final String logLine;

    private ProtocolException(String cause, String message, String logKey, boolean voteError, String logLine) {
        super(message, null, false, false);
        this.cause = cause;
        this.logKey = logKey;
        this.voteError = voteError;
        this.logLine = logLine;
    }

    /**
     * A list's mistake, such as a wrong token.
     *
     * @param message the reply a v2 list gets, in NuVotifier's words
     * @param key errors with the same key are logged once: the list's service name, or its address for v1
     * @param logLine what the console is told, naming the list
     */
    static ProtocolException vote(String cause, String message, String key, String logLine) {
        return new ProtocolException(cause, message, key, true, logLine);
    }

    /**
     * Something that wasn't a vote at all.
     *
     * @param kind logged once per kind; never an address or a name the sender chose
     */
    static ProtocolException noise(String cause, String message, String kind) {
        return new ProtocolException(cause, message, kind, false, message);
    }

    String causeName() {
        return cause;
    }

    String logKey() {
        return logKey;
    }

    boolean voteError() {
        return voteError;
    }

    /** What the console is told. */
    String logLine() {
        return logLine;
    }
}
