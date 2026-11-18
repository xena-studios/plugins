package co.xenastudios.xbackup.store;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A repository in a folder: on the server's disk, a second disk or a network mount. Files
 * are written to {@code tmp/}, flushed to disk, then renamed into place, so a crash leaves
 * either the whole file or none of it under its name.
 */
public final class LocalBackend implements Backend {

    static final String TMP = "tmp";

    private final Path root;

    public LocalBackend(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public String describe() {
        return "the folder " + root;
    }

    /** A new, empty file name in the backend's own {@code tmp/}, on the same disk as its files. */
    public Path newTemp(String suffix) throws IOException {
        Path tmp = root.resolve(TMP);
        Files.createDirectories(tmp);
        return tmp.resolve(UUID.randomUUID() + suffix);
    }

    @Override
    public void put(String name, Path file) throws IOException {
        Path target = resolve(name);
        Path source = file.toAbsolutePath().normalize();
        if (!source.startsWith(root.resolve(TMP))) {
            // Copy onto this disk first, so the final step is a rename.
            Path copy = newTemp(".part");
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            source = copy;
        }
        sync(source);
        Files.createDirectories(target.getParent());
        move(source, target);
        syncDirectory(target.getParent());
    }

    @Override
    public void put(String name, byte[] data) throws IOException {
        Path temp = newTemp(".part");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Path target = resolve(name);
        Files.createDirectories(target.getParent());
        move(temp, target);
        syncDirectory(target.getParent());
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            // Some network mounts can't replace in one step. Content-named files are the same
            // bytes whoever wrote them, so a plain replace is still safe.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public InputStream get(String name) throws IOException {
        return Files.newInputStream(resolve(name));
    }

    @Override
    public byte[] read(String name, long offset, int length) throws IOException {
        try (FileChannel channel = FileChannel.open(resolve(name), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            long position = offset;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, position);
                if (n < 0) {
                    throw new EOFException(name + " is shorter than " + (offset + length) + " bytes");
                }
                position += n;
            }
            return buffer.array();
        }
    }

    @Override
    public List<FileInfo> list(String prefix) throws IOException {
        List<FileInfo> files = new ArrayList<>();
        // List from the deepest folder the prefix names, then filter.
        int slash = prefix.lastIndexOf('/');
        Path start = slash < 0 ? root : root.resolve(prefix.substring(0, slash));
        if (!Files.isDirectory(start)) {
            return files;
        }
        Path tmp = root.resolve(TMP);
        try (Stream<Path> walk = Files.walk(start)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (path.startsWith(tmp) || !Files.isRegularFile(path)) {
                    continue;
                }
                String name = root.relativize(path).toString().replace('\\', '/');
                if (name.startsWith(prefix)) {
                    try {
                        files.add(new FileInfo(name, Files.size(path)));
                    } catch (NoSuchFileException gone) {
                        // Deleted while listing.
                    }
                }
            }
        }
        return files;
    }

    @Override
    public void delete(String name) throws IOException {
        Files.deleteIfExists(resolve(name));
    }

    @Override
    public Path localFile(String name) {
        return resolve(name);
    }

    /** Clears what crashed writes left in {@code tmp/}. Only while nothing is writing. */
    public void clearTemp() throws IOException {
        Path tmp = root.resolve(TMP);
        if (!Files.isDirectory(tmp)) {
            return;
        }
        try (Stream<Path> files = Files.list(tmp)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.deleteIfExists(file);
            }
        }
    }

    private Path resolve(String name) {
        return root.resolve(Backend.checkName(name));
    }

    /** Flushes a file's bytes to the disk. */
    static void sync(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** Flushes a folder's entries, so a rename survives a power cut. Not every system can. */
    static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows can't open a folder this way; its renames are durable on their own.
        }
    }

    /** Writes a stream to a new file, for backends that need a local copy first. */
    static long copy(InputStream in, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            return in.transferTo(out);
        }
    }
}
