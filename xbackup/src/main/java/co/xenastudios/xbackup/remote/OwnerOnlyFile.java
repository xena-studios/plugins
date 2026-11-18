package co.xenastudios.xbackup.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
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
                // Windows: made, then narrowed to its owner before anything is written to it.
                Files.createFile(temp);
                ownerOnlyAcl(temp);
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                // On disk before the rename, so a power cut never leaves an empty file in its place.
                channel.force(true);
            }
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
        } catch (UnsupportedOperationException e) {
            try {
                ownerOnlyAcl(file);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Not ours to change.
            }
        } catch (IOException e) {
            // Not ours to change.
        }
    }

    // Windows: one entry, the owner's, with nothing inherited from the folder.
    private static void ownerOnlyAcl(Path file) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) {
            return;
        }
        UserPrincipal owner = view.getOwner();
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        view.setAcl(List.of(entry));
    }
}
