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

    /**
     * A staged restore.
     *
     * @param done how many of the entries are already in place
     */
    public record Pending(String snapshot, String created, List<Entry> entries, int done) {}

    /** The journal of a restore that failed and was undone, kept for reference. */
    public static final String FAILED = "failed";

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
        syncFolder(restoreFolder);
    }

    /** The staged restore waiting in the folder, or null. */
    public static Pending pending(Path restoreFolder) throws IOException {
        Journal journal = Journal.read(restoreFolder);
        if (journal == null) {
            return null;
        }
        int done = 0;
        for (int i = 0; i < journal.entries().size(); i++) {
            done += journal.done(i) ? 1 : 0;
        }
        return new Pending(journal.snapshot, journal.created, journal.entries, done);
    }

    // The journal: the properties, then a line per step taken: "aside <n>" once an entry's
    // old files are set aside (or there were none), "done <n>" once it's in place, "undone <n>"
    // once a failed restore put it back. The last line about an entry is its state.
    private static final int NONE = 0;
    private static final int ASIDE = 1;
    private static final int DONE = 2;

    private record Journal(String snapshot, String created, List<Entry> entries, int[] state) {

        boolean done(int i) {
            return state[i] == DONE;
        }

        static Journal read(Path restoreFolder) throws IOException {
            Path file = restoreFolder.resolve(PENDING);
            if (!Files.exists(file)) {
                return null;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            List<String> steps = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (line.startsWith("done ") || line.startsWith("undone ") || line.startsWith("aside ")) {
                    steps.add(line.strip());
                } else {
                    head.append(line).append('\n');
                }
            }
            Properties properties = new Properties();
            try (Reader reader = new StringReader(head.toString())) {
                properties.load(reader);
            }
            int count;
            try {
                count = Integer.parseInt(properties.getProperty("count", "0"));
            } catch (NumberFormatException e) {
                throw new IOException("The restore journal " + file + " is damaged.");
            }
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String path = properties.getProperty("path." + i);
                if (path == null) {
                    throw new IOException("The restore journal " + file + " is damaged.");
                }
                String clean;
                try {
                    clean = Restore.normalise(path);
                } catch (IllegalArgumentException e) {
                    throw new IOException("The restore journal " + file + " has a path outside the server folder: " + path);
                }
                entries.add(new Entry(clean, Boolean.parseBoolean(properties.getProperty("database." + i))));
            }
            int[] state = new int[count];
            for (String step : steps) {
                // A cut-off last line (a crash while it was written) is ignored.
                String[] parts = step.split(" ");
                try {
                    int index = Integer.parseInt(parts[1]);
                    if (index >= 0 && index < count) {
                        state[index] = switch (parts[0]) {
                            case "done" -> DONE;
                            case "aside" -> ASIDE;
                            default -> NONE;
                        };
                    }
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    // Ignored, as above.
                }
            }
            return new Journal(properties.getProperty("snapshot", "?"), properties.getProperty("created", "unknown"),
                entries, state);
        }
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

    /** A restore that couldn't be put in place. What was there before is back where it was. */
    public static final class FailedException extends IOException {
        FailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A restore that failed part way, and then couldn't be undone either: the server folder
     * is half restored. Never start a server on it.
     */
    public static final class StuckException extends IOException {
        StuckException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Where one entry's files live and go. */
    private record Places(Path target, Path from, Path old) {}

    private static Places places(Entry entry, Path root, Path staged, Path aside, String created) throws IOException {
        Path target = entry.path().isEmpty() ? root : root.resolve(entry.path());
        Path from = staged.resolve(entry.path());
        Path old = aside.resolve(entry.path());
        // A folder the owner keeps elsewhere behind a link (a world on another disk) is
        // restored where the link leads, so it stays there; what was in it goes beside it,
        // on the same disk. The link itself is left as it is.
        if (!entry.path().isEmpty() && Files.isSymbolicLink(target)) {
            Path linked = target.toAbsolutePath().getParent().resolve(Files.readSymbolicLink(target)).normalize();
            old = linked.resolveSibling(linked.getFileName() + ".replaced-" + created);
            target = linked;
        }
        return new Places(target, from, old);
    }

    /**
     * Puts the staged restore in place, all or nothing.
     *
     * <p>Everything is checked first: if a staged copy is missing, nothing is touched. If a
     * step fails part way (a file the system keeps locked, a disk that fills), what was put
     * in place is moved back to the staging folder and what was set aside is moved back, so
     * the server folder is as it was; the journal is then kept as {@value #FAILED}, the
     * staged copy deleted, and {@link FailedException} thrown. If even that can't be done,
     * {@link StuckException}: stop, and sort it out by hand from the paths it gives. A crash
     * at any point is finished on the next run.
     *
     * @param root the server folder the journal's paths are relative to
     * @return where the old files went, or null if nothing was pending
     */
    public static Path apply(Path restoreFolder, Path root, Consumer<String> log) throws IOException {
        Journal journal = Journal.read(restoreFolder);
        if (journal == null) {
            return null;
        }
        Path file = restoreFolder.resolve(PENDING);
        Path staged = restoreFolder.resolve(STAGED);
        Path aside = restoreFolder.resolve(REPLACED).resolve(journal.created());
        List<Places> places = new ArrayList<>();
        for (Entry entry : journal.entries()) {
            places.add(places(entry, root, staged, aside, journal.created()));
        }
        // Check before moving anything: every entry not started has its staged copy.
        for (int i = 0; i < places.size(); i++) {
            Places at = places.get(i);
            if (journal.state()[i] == NONE && !exists(at.from())) {
                throw new IOException("The staged copy of " + display(journal.entries().get(i)) + " is missing from "
                    + staged + ", so the restore can't be put in place. Nothing was changed. Stage it again, or delete "
                    + file + " to cancel it.");
            }
        }
        List<Integer> touched = new ArrayList<>();
        int current = -1;
        try {
            for (int i = 0; i < places.size(); i++) {
                if (journal.done(i)) {
                    continue;
                }
                current = i;
                Entry entry = journal.entries().get(i);
                Places at = places.get(i);
                touched.add(i);
                deleteTree(sibling(at.target(), MOVED));
                if (journal.state()[i] == ASIDE) {
                    // Started before a crash: set aside, and maybe put in place too.
                    if (!exists(at.from()) && !exists(at.target())) {
                        throw new IOException("the staged copy of " + display(entry) + " disappeared part way");
                    }
                } else if (exists(at.target()) && !exists(at.old())) {
                    move(at.target(), at.old());
                }
                if (entry.database()) {
                    for (String suffix : DATABASE_FILES) {
                        Path sibling = sibling(at.target(), suffix);
                        Path oldSibling = sibling(at.old(), suffix);
                        if (exists(sibling) && !exists(oldSibling)) {
                            move(sibling, oldSibling);
                        }
                    }
                }
                if (journal.state()[i] != ASIDE) {
                    step(file, "aside " + i);
                }
                if (exists(at.from())) {
                    if (exists(at.target())) {
                        // Set aside across disks before a crash, which left part of it behind.
                        deleteTree(at.target());
                    }
                    move(at.from(), at.target());
                }
                step(file, "done " + i);
                log.accept("Restored " + display(entry) + ".");
            }
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String what = current < 0 ? "the restore" : display(journal.entries().get(current));
            try {
                undo(journal, places, touched, file);
                finishFailed(restoreFolder);
            } catch (IOException | RuntimeException undoing) {
                undoing.addSuppressed(e);
                throw new StuckException("Putting " + what + " in place failed (" + why + "), and undoing the restore"
                    + " failed too (" + undoing.getMessage() + "). The server folder is part way restored: don't start"
                    + " the server on it. The journal is " + file + ", the restored copies are in " + staged
                    + ", and what was there before is in " + aside + ".", undoing);
            }
            throw new FailedException("Putting " + what + " in place failed (" + why + "), so the restore was undone:"
                + " everything is as it was before. The restore's journal is kept as " + restoreFolder.resolve(FAILED)
                + ". Fix the cause, then restore again.", e);
        }
        Files.delete(file);
        deleteTree(staged);
        return aside;
    }

    // Moves every entry this run touched back: the restored copy to the staging folder, then
    // what was set aside back into place. Newest first, each step journaled.
    private static void undo(Journal journal, List<Places> places, List<Integer> touched, Path file) throws IOException {
        for (int t = touched.size() - 1; t >= 0; t--) {
            int i = touched.get(t);
            Entry entry = journal.entries().get(i);
            Places at = places.get(i);
            if (exists(at.from())) {
                // Not put in place. Anything at the target beside a set-aside copy is what a
                // move across disks left behind.
                if (exists(at.old()) && exists(at.target())) {
                    deleteTree(at.target());
                }
            } else if (exists(at.target())) {
                move(at.target(), at.from());
            }
            if (exists(at.old()) && !exists(at.target())) {
                move(at.old(), at.target());
            }
            if (entry.database()) {
                for (String suffix : DATABASE_FILES) {
                    Path sibling = sibling(at.target(), suffix);
                    Path oldSibling = sibling(at.old(), suffix);
                    if (exists(oldSibling) && !exists(sibling)) {
                        move(oldSibling, sibling);
                    }
                }
            }
            step(file, "undone " + i);
        }
    }

    private static void finishFailed(Path restoreFolder) throws IOException {
        Files.move(restoreFolder.resolve(PENDING), restoreFolder.resolve(FAILED), StandardCopyOption.REPLACE_EXISTING);
        deleteTree(restoreFolder.resolve(STAGED));
    }

    private static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private static String display(Entry entry) {
        return entry.path().isEmpty() ? "the server folder" : entry.path();
    }

    /** For tests: called before every step that changes the disk, with the step's number. */
    static volatile java.util.function.IntConsumer beforeStep;
    private static final java.util.concurrent.atomic.AtomicInteger STEPS = new java.util.concurrent.atomic.AtomicInteger();

    private static void hook() {
        java.util.function.IntConsumer hook = beforeStep;
        if (hook != null) {
            hook.accept(STEPS.getAndIncrement());
        }
    }

    private static void step(Path journal, String line) throws IOException {
        hook();
        Files.writeString(journal, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        force(journal);
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
        hook();
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            syncFolder(to.getParent());
            syncFolder(from.getParent());
            return;
        } catch (AtomicMoveNotSupportedException e) {
            // Different disks: copy instead.
        }
        Path temp = to.resolveSibling(to.getFileName() + ".xbackup-part");
        deleteTree(temp);
        try {
            copyTree(from, temp);
            Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            deleteTree(temp);
            throw e;
        }
        syncFolder(to.getParent());
        // Renamed first, so a crash while deleting never leaves part of it under its name.
        Path gone = from.resolveSibling(from.getFileName() + MOVED);
        deleteTree(gone);
        Files.move(from, gone, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(gone);
    }

    /** What a move across disks leaves while it deletes the source; cleared on the next run. */
    private static final String MOVED = ".xbackup-moved";

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

    /** Flushes a folder's entries, so a rename into it survives a power cut. Not every system can. */
    static void syncFolder(Path folder) {
        if (folder == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows can't open a folder this way; its renames are durable on their own.
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
