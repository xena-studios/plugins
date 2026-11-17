package co.xenastudios.core.db;

/** The database couldn't be opened or brought up to date. The message says why, for the console. */
public final class DatabaseException extends Exception {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(String message) {
        super(message);
    }
}
