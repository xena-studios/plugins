package co.xenastudios.core.loader;

/**
 * A library couldn't be made available: offline, a download that didn't match its pinned
 * SHA-256, a JDK that refuses every way of adding it. The message is written for the server's
 * owner and says what to do, so it's logged without a stack trace.
 */
public final class LibraryException extends Exception {

    private static final long serialVersionUID = 1L;

    public LibraryException(String message) {
        super(message);
    }

    public LibraryException(String message, Throwable cause) {
        super(message, cause);
    }
}
