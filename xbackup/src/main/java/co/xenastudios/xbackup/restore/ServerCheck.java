package co.xenastudios.xbackup.restore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a server is running, for the command-line restore, which must never swap folders
 * under a live server. Two signs, either is enough:
 *
 * <ul>
 * <li>xBackup's own {@value #MARKER}, locked by the operating system from the moment the
 *     server starts (the bootstrap) until the process exits, wherever the worlds are. The
 *     command-line restore holds it too while it works, so a server started meanwhile stops
 *     in its bootstrap;
 * <li>a world's {@code session.lock}, which Minecraft locks while the world is loaded:
 *     looked for in the folders being restored, a few folders deep in the server folder, and
 *     in {@code bukkit.yml}'s {@code world-container}.
 * </ul>
 *
 * <p>The operating system drops both locks if the server dies, so a crashed server never
 * looks like it's still running.
 */
public final class ServerCheck {

    /** In xBackup's folder: locked while the server runs. */
    public static final String MARKER = "running.lock";
    private static final int DEPTH = 3;
    private static final Pattern WORLD_CONTAINER = Pattern.compile("(?m)^\\s*world-container:\\s*['\"]?([^'\"#\\r\\n]+?)['\"]?\\s*(#.*)?$");

    private static FileChannel heldChannel;
    private static FileLock held;

    private ServerCheck() {}

    /** What {@link #hold} found. */
    public enum Hold {
        /** This process holds the marker. */
        HELD,
        /** Another process holds it: a command-line restore, or another server on this folder. */
        BUSY,
        /** It couldn't be locked at all (a file system without locks, or no permission). */
        UNAVAILABLE
    }

    /**
     * Locks the marker for as long as this server runs: the operating system lets go when the
     * process exits, after the worlds are saved, so it's never released on disable. Safe to
     * call more than once.
     */
    public static synchronized Hold hold(Path home) {
        if (held != null && held.isValid()) {
            return Hold.HELD;
        }
        try {
            Files.createDirectories(home);
            FileChannel channel = FileChannel.open(home.resolve(MARKER), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            } catch (IOException e) {
                channel.close();
                return Hold.UNAVAILABLE;
            }
            if (lock == null) {
                channel.close();
                return Hold.BUSY;
            }
            heldChannel = channel;
            held = lock;
            return Hold.HELD;
        } catch (IOException e) {
            // Only a hint for the command line; session.lock still says the server is up.
            return Hold.UNAVAILABLE;
        }
    }

    /** The marker, held by the command-line restore while it works; closing lets go. */
    public interface Claim extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    /**
     * Takes the marker for the command-line restore, for as long as it runs, so a server
     * started meanwhile stops in its bootstrap instead of loading worlds being swapped.
     *
     * @return the held marker, or null if a server (or another restore) holds it
     * @throws IOException if it can't be locked at all, so there's no telling
     */
    public static Claim claim(Path home) throws IOException {
        Files.createDirectories(home);
        FileChannel channel = FileChannel.open(home.resolve(MARKER), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException e) {
            channel.close();
            throw new IOException("can't lock " + home.resolve(MARKER) + " (" + e.getMessage() + "), so there's no"
                + " telling whether the server is running. Restore into an empty folder with --to instead.", e);
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        FileLock taken = lock;
        return () -> {
            try {
                taken.release();
            } finally {
                channel.close();
            }
        };
    }

    /**
     * Why the server in this folder looks like it's running, or null if it doesn't.
     *
     * @param paths the paths about to be restored, relative to the server folder
     */
    public static String running(Path server, List<String> paths) throws IOException {
        if (!Files.isDirectory(server)) {
            return null;
        }
        Path marker = server.resolve("plugins").resolve("xBackup").resolve(MARKER);
        if (Files.isRegularFile(marker) && locked(marker)) {
            return "xBackup holds " + server.relativize(marker);
        }
        return worldOpen(server, paths);
    }

    /**
     * Which world's {@code session.lock} is locked, or null: the check that needs no marker,
     * for the command-line restore once it holds the marker itself.
     *
     * @param paths the paths about to be restored, relative to the server folder
     */
    public static String worldOpen(Path server, List<String> paths) {
        if (!Files.isDirectory(server)) {
            return null;
        }
        Set<Path> folders = new LinkedHashSet<>();
        folders.add(server);
        for (String path : paths) {
            folders.add(path.isEmpty() ? server : server.resolve(path));
        }
        Path container = worldContainer(server);
        if (container != null) {
            folders.add(container);
        }
        List<Path> locks = new ArrayList<>();
        for (Path folder : folders) {
            find(folder, DEPTH, locks);
        }
        for (Path lock : locks) {
            if (locked(lock)) {
                return (lock.startsWith(server) ? server.relativize(lock) : lock) + " is locked";
            }
        }
        return null;
    }

    // Every session.lock in the folder and the folders below it, down to the depth given.
    private static void find(Path folder, int depth, List<Path> locks) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(folder)) {
            return;
        }
        Path lock = folder.resolve("session.lock");
        if (Files.isRegularFile(lock) && !locks.contains(lock)) {
            locks.add(lock);
        }
        if (depth == 0) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, Files::isDirectory)) {
            for (Path child : stream) {
                find(child, depth - 1, locks);
            }
        } catch (IOException e) {
            // A folder we can't read can't be a world the server has open, as far as we can tell.
        }
    }

    // bukkit.yml's settings.world-container, if it's set.
    static Path worldContainer(Path server) {
        Path bukkit = server.resolve("bukkit.yml");
        if (!Files.isRegularFile(bukkit)) {
            return null;
        }
        try {
            Matcher matcher = WORLD_CONTAINER.matcher(Files.readString(bukkit, StandardCharsets.UTF_8));
            if (matcher.find()) {
                String value = matcher.group(1).strip();
                return value.isEmpty() ? null : server.resolve(value).normalize();
            }
        } catch (IOException | java.nio.file.InvalidPathException e) {
            // Unreadable: the other checks still run.
        }
        return null;
    }

    // Whether another process holds the file locked.
    private static boolean locked(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            // Windows refuses to open a locked file at all.
            return true;
        }
    }

    /** Why {@code --to} can't be used, or null: it must be new or empty, and not hold the server. */
    public static String notEmpty(Path to, Path server) throws IOException {
        if (server.startsWith(to)) {
            return "--to " + to + " holds the server folder; give a new or empty folder.";
        }
        if (!Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
            return "--to " + to + " isn't a folder; give a new or empty folder.";
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(to)) {
            if (stream.iterator().hasNext()) {
                return "--to " + to + " isn't empty, and a restore there could mix with what's in it. Give a new or"
                    + " empty folder.";
            }
        }
        return null;
    }
}
