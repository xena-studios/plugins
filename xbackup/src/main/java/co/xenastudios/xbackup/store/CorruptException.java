package co.xenastudios.xbackup.store;

import java.io.IOException;

/** Something in the repository isn't what it should be: a bad hash, a truncated file, a missing blob. */
public final class CorruptException extends IOException {

    public CorruptException(String message) {
        super(message);
    }

    public CorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
