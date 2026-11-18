package co.xenastudios.xbackup.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Files that hold secrets: readable by their owner only from the moment they exist. */
public final class OwnerOnlyFile {

    private OwnerOnlyFile() {}

    /**
     * Writes the text, swapped in whole. The new file is created owner-only (where the
     * system has permissions), never made first and narrowed after, so there's no moment
     * another user could open it.
     */
    public static void write(Path file, String text) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temp = folder.resolve(file.getFileName() + "." + UUID.randomUUID().toString().substring(0, 8) + ".part");
        try {
            try {
                Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                // Windows: the folder's permissions apply.
                Files.createFile(temp);
            }
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Narrows an existing file to its owner, where the system has permissions. */
    public static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows, or not ours to change.
        }
    }
}
