package co.xenastudios.xbackup.store;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a repository's files live: a folder, a bucket, a server. Every file is written once
 * and never changed, so a backend only needs to put, get, list and delete whole files.
 *
 * <p>Names are relative paths with {@code /} between parts, such as
 * {@code data/ab/ab12...}. A put must be all or nothing: a file is visible under its name
 * only once all of it is there.
 */
public interface Backend extends Closeable {

    /** For messages: "the folder /srv/backups", "S3 bucket backups at r2.example.com". */
    String describe();

    /** Stores the file's bytes under the name, replacing any file already there. */
    void put(String name, Path file) throws IOException;

    /** Stores the bytes under the name, replacing any file already there. */
    void put(String name, byte[] data) throws IOException;

    /**
     * The file's bytes from the start.
     *
     * @throws java.nio.file.NoSuchFileException if there's no such file
     */
    InputStream get(String name) throws IOException;

    /**
     * {@code length} bytes of the file from {@code offset}.
     *
     * @throws java.nio.file.NoSuchFileException if there's no such file
     * @throws java.io.EOFException if the file is shorter
     */
    byte[] read(String name, long offset, int length) throws IOException;

    /** Every file whose name starts with the prefix, at any depth, in no particular order. */
    List<FileInfo> list(String prefix) throws IOException;

    /** Removes the file. A file that isn't there is fine. */
    void delete(String name) throws IOException;

    /** The file on the local disk, if this backend keeps files there; otherwise null. */
    default Path localFile(String name) {
        return null;
    }

    @Override
    default void close() throws IOException {}

    /** A file and its size in bytes. */
    record FileInfo(String name, long size) {}

    /** Throws if the name could escape the backend's root or isn't a plain relative path. */
    static String checkName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.endsWith("/") || name.contains("\\")
            || name.contains("//")) {
            throw new IllegalArgumentException("not a repository file name: " + name);
        }
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("not a repository file name: " + name);
            }
        }
        return name;
    }
}
