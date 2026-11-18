package co.xenastudios.xbackup.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * A place xBackup can copy its repository to. Every file is written once and never
 * changed, so a backend only needs to put, get, list and delete whole files. Called on
 * xBackup's own threads, never a game thread.
 *
 * <p>Names are relative paths with {@code /}, such as {@code data/ab/ab12...}. A put must be
 * all or nothing: a file only appears under its name once all of it is there.
 *
 * @since 1.0.0
 */
public interface StorageBackend {

    /**
     * For messages, such as "my bucket at example.com".
     *
     * @return a short description
     * @since 1.0.0
     */
    String describe();

    /**
     * Stores the file under the name, replacing any file already there.
     *
     * @param name where to store it
     * @param file the local file to send
     * @throws IOException if it couldn't be stored
     * @since 1.0.0
     */
    void put(String name, Path file) throws IOException;

    /**
     * Reads a file.
     *
     * @param name the file
     * @return its bytes from the start
     * @throws java.nio.file.NoSuchFileException if there's no such file
     * @throws IOException if it couldn't be read
     * @since 1.0.0
     */
    InputStream get(String name) throws IOException;

    /**
     * Lists files.
     *
     * @param prefix names to list start with this
     * @return every file under the prefix, at any depth
     * @throws IOException if it couldn't be listed
     * @since 1.0.0
     */
    List<StoredFile> list(String prefix) throws IOException;

    /**
     * Removes a file. A file that isn't there is fine.
     *
     * @param name the file
     * @throws IOException if it couldn't be removed
     * @since 1.0.0
     */
    void delete(String name) throws IOException;

    /**
     * A stored file.
     *
     * @param name its name
     * @param size its size in bytes
     * @since 1.0.0
     */
    record StoredFile(String name, long size) {}
}
