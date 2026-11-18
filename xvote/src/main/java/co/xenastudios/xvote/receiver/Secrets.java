package co.xenastudios.xvote.receiver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Files only the server's own user may read: the private key, the tokens, and their
 * backups. Made with those permissions from the start, so there's no moment another user
 * on the machine could read them. Where the file system has no such permissions
 * (Windows, some mounts), the folder's own apply.
 */
public final class Secrets {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private Secrets() {}

    /** Writes the text to a new file only the owner can read, replacing any file there. */
    public static void write(Path file, String text) throws IOException {
        Files.deleteIfExists(file);
        try {
            FileAttribute<Set<PosixFilePermission>> attribute = PosixFilePermissions.asFileAttribute(OWNER_ONLY);
            Files.createFile(file, attribute);
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        } catch (FileAlreadyExistsException e) {
            // Made between the delete and here; its permissions are set below.
        }
        ownerOnly(file);
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Makes an existing file readable by its owner only, where the file system allows. */
    public static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows, or a mount that doesn't keep permissions.
        }
    }
}
