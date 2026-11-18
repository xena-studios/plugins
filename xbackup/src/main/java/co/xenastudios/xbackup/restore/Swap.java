package co.xenastudios.xbackup.restore;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Puts a staged restore in place: each restored path's current files are moved aside to
 * {@code replaced/<time>/}, then the staged copy is moved in. Runs from the command line with
 * the server stopped, or from the plugin's bootstrap on the next start, before any world
 * loads.
 *
 * <p>A journal ({@code pending}) lists the paths, and each finished path is appended to it,
 * so a crash part way through is finished on the next run rather than left half done. Every
 * step can be repeated safely: a path already moved aside isn't moved again.
 *
 * <pre>
 * restore/
 *   pending                 the journal: what to swap, and which are done
 *   staged/&lt;path&gt;           the restored files, waiting
 *   replaced/&lt;time&gt;/&lt;path&gt;   what was there before
 * </pre>
 */
public final class Swap {

    public static final String PENDING = "pending";
    public static final String STAGED = "staged";
    public static final String REPLACED = "replaced";
    private static final String[] DATABASE_FILES = {"-wal", "-shm", "-journal"};

    private Swap() {}

    /**
     * One path to swap.
     *
     * @param database a SQLite file restored on its own: its old journal files are set aside
     *     too, or SQLite would replay them over the restored copy
     */
    public record Entry(String path, boolean database) {}

    /** A staged restore. */
    public record Pending(String snapshot, String created, List<Entry> entries, int done) {}

    /** Writes the journal once everything is staged: from then on the swap will happen. */
    public static void schedule(Path restoreFolder, String snapshot, List<Entry> entries) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("snapshot", snapshot);
        properties.setProperty("created", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
        properties.setProperty("count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            properties.setProperty("path." + i, entries.get(i).path());
            properties.setProperty("database." + i, Boolean.toString(entries.get(i).database()));
        }
        StringBuilder text = new StringBuilder("# A restore xBackup will put in place on the next start. Delete this\n"
            + "# file (or run /backup restore cancel) to cancel it.\n");
        java.io.StringWriter writer = new java.io.StringWriter();
        properties.store(writer, null);
        text.append(writer);
        Path temp = restoreFolder.resolve(PENDING + ".part");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        force(temp);
        Files.move(temp, restoreFolder.resolve(PENDING), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The staged restore waiting in the folder, or null. */
    public static Pending pending(Path restoreFolder) throws IOException {
        Path journal = restoreFolder.resolve(PENDING);
        if (!Files.exists(journal)) {
            return null;
        }
        String text = Files.readString(journal, StandardCharsets.UTF_8);
        // The properties, then one "done <n>" line per finished path.
        StringBuilder head = new StringBuilder();
        int done = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("done ")) {
                done = Math.max(done, Integer.parseInt(line.substring(5).strip()) + 1);
            } else {
                head.append(line).append('\n');
            }
        }
        Properties properties = new Properties();
        try (Reader reader = new StringReader(head.toString())) {
            properties.load(reader);
        }
        int count = Integer.parseInt(properties.getProperty("count", "0"));
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String path = properties.getProperty("path." + i);
            if (path == null) {
                throw new IOException("The restore journal " + journal + " is damaged.");
            }
            entries.add(new Entry(Restore.normalise(path), Boolean.parseBoolean(properties.getProperty("database." + i))));
        }
        return new Pending(properties.getProperty("snapshot", "?"), properties.getProperty("created", "unknown"),
            entries, done);
    }

    /** Cancels a staged restore that hasn't started: deletes the journal and the staged files. */
    public static boolean cancel(Path restoreFolder) throws IOException {
        Pending pending = pending(restoreFolder);
        if (pending == null) {
            return false;
        }
        if (pending.done() > 0) {
            throw new IOException("This restore is part way done; it has to finish. Restart the server.");
        }
        Files.delete(restoreFolder.resolve(PENDING));
        deleteTree(restoreFolder.resolve(STAGED));
        return true;
    }

    /**
     * Puts the staged restore in place.
     *
     * @param root the server folder the journal's paths are relative to
     * @return where the old files went, or null if nothing was pending
     */
    public static Path apply(Path restoreFolder, Path root, Consumer<String> log) throws IOException {
        Pending pending = pending(restoreFolder);
        if (pending == null) {
            return null;
        }
        Path journal = restoreFolder.resolve(PENDING);
        Path staged = restoreFolder.resolve(STAGED);
        Path aside = restoreFolder.resolve(REPLACED).resolve(pending.created());
        for (int i = pending.done(); i < pending.entries().size(); i++) {
            Entry entry = pending.entries().get(i);
            Path target = entry.path().isEmpty() ? root : root.resolve(entry.path());
            Path from = staged.resolve(entry.path());
            Path old = aside.resolve(entry.path());
            if (exists(target) && !exists(old)) {
                move(target, old);
            }
            if (entry.database()) {
                for (String suffix : DATABASE_FILES) {
                    Path sibling = target.resolveSibling(target.getFileName() + suffix);
                    Path oldSibling = old.resolveSibling(old.getFileName() + suffix);
                    if (exists(sibling) && !exists(oldSibling)) {
                        move(sibling, oldSibling);
                    }
                }
            }
            if (exists(from)) {
                move(from, target);
            } else if (!exists(target)) {
                throw new IOException("The staged copy of " + entry.path() + " is missing; the restore can't finish."
                    + " What was there before is in " + aside + ".");
            }
            Files.writeString(journal, "done " + i + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            force(journal);
            log.accept("Restored " + (entry.path().isEmpty() ? "the server folder" : entry.path()) + ".");
        }
        Files.delete(journal);
        deleteTree(staged);
        return aside;
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Moves a file or folder. A rename when both ends are on one disk; otherwise a copy to a
     * temporary name beside the target, a rename into place, then deleting the source, so a
     * crash never leaves a half-copied target under the real name.
     */
    static void move(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException e) {
            // Different disks: copy instead.
        }
        Path temp = to.resolveSibling(to.getFileName() + ".xbackup-part");
        deleteTree(temp);
        copyTree(from, temp);
        Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(from);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Deletes a file or a folder and everything in it. Missing is fine. */
    public static void deleteTree(Path path) throws IOException {
        if (!exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
