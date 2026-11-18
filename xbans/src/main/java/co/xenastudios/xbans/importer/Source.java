package co.xenastudios.xbans.importer;

import java.nio.file.Path;

/** Somewhere punishments can be imported from. */
interface Source {

    /** As typed in /xbans import. */
    String name();

    /**
     * Reads everything off the server's threads.
     *
     * @param server the server's folder, where banned-players.json is
     * @param plugins the plugins folder
     * @throws ImportException with a reason for the owner, if it can't be read
     */
    Haul read(Path server, Path plugins, String scope) throws ImportException;

    final class ImportException extends Exception {
        ImportException(String message) {
            super(message);
        }

        ImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
