package co.xenastudios.xbackup.backup;

import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import co.xenastudios.xbackup.store.Tree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One backup, start to finish. Pure Java: the server's part (pausing autosave and flushing
 * worlds) comes in through {@link Pause}.
 *
 * <ol>
 * <li>First pass over the worlds while the server runs normally: store whatever changed
 *     since the last snapshot and could be read consistently.
 * <li>Pause: autosave off, worlds flushed to disk ({@link Pause#pause}).
 * <li>Second pass over the worlds, strict: only what changed since the first pass is read.
 * <li>Autosave back on ({@link Pause#resume}), always, even on failure.
 * <li>Everything else (plugins, server files, databases), then the snapshot.
 * <li>The packs just written are read back and checked before the snapshot is written.
 * </ol>
 */
public final class Backup {

    private Backup() {}

    /** The server's part of a backup. */
    public interface Pause {

        /**
         * Stops autosave and flushes the worlds, so their files hold one moment and nothing
         * writes them until {@link #resume}.
         *
         * @return how long the server's threads spent on it, in milliseconds
         */
        long pause() throws Exception;

        /** Turns autosave back on. Called once after every {@link #pause}, even a failed one. */
        void resume();

        Pause NONE = new Pause() {
            @Override
            public long pause() {
                return 0;
            }

            @Override
            public void resume() {}
        };
    }

    /** Pauses another plugin's writes while its folder is read. */
    public interface Quiet {
        void pause() throws Exception;

        void resume();
    }

    /**
     * What to back up and how.
     *
     * @param root the server folder; every path is relative to it
     * @param worlds world save folders, read during the pause
     * @param others everything else to take
     * @param excludes glob patterns of paths to leave out
     * @param skip absolute paths never taken (the repository itself)
     * @param quiet folders to read with their plugin's writes paused
     * @param temp where database copies go while they're stored
     */
    public record Plan(Path root, List<String> worlds, List<String> others, List<String> excludes, Set<Path> skip,
                       Map<String, Quiet> quiet, String reason, String source, Path temp, Throttle throttle,
                       Session.Options options) {}

    /** A finished backup and what it took. */
    public record Result(Snapshot snapshot, long chunksRead, long chunksReused, long pass1Millis) {}

    /**
     * Runs a backup. Hold the repository's exclusive lock.
     *
     * @param databases copies live SQLite databases; null leaves them out with a warning
     */
    public static Result run(Repository repository, Plan plan, Pause pause, Databases databases,
                             BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        Instant start = Instant.now();
        List<String> problems = new ArrayList<>();
        List<Snapshot> snapshots = repository.snapshots(problems);
        Snapshot parent = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        Reference previous = parent == null ? Reference.NONE
            : Reference.of(repository, parent.tree(), parent.time().toEpochMilli());
        Path root = plan.root().toAbsolutePath().normalize();
        Selection worldSelection = new Selection(plan.worlds(), plan.excludes(), plan.skip());

        try (Session session = repository.session(plan.options())) {
            // 1. First pass, while the server saves as usual.
            progress.accept("reading the worlds");
            long pass1Start = System.currentTimeMillis();
            Scanner first = new Scanner(session, previous, false, worldSelection, Map.of(), plan.throttle(), null,
                plan.temp(), cancelled);
            for (String world : plan.worlds()) {
                first.scan(root.resolve(world), world);
            }
            long pass1Millis = System.currentTimeMillis() - pass1Start;

            // 2-4. The pause: flush, read what changed since the first pass, resume.
            progress.accept("saving the worlds");
            Map<String, Tree.Node> worlds = new LinkedHashMap<>();
            long pauseMillis;
            long offStart = System.nanoTime();
            long offMillis;
            Scanner second;
            try {
                pauseMillis = pause.pause();
                progress.accept("reading what changed during the pause");
                Reference overlay = Reference.overlay(first.nodes, first.regions, pass1Start, previous);
                second = new Scanner(session, overlay, true, worldSelection, Map.of(), plan.throttle(), null,
                    plan.temp(), cancelled);
                for (String world : plan.worlds()) {
                    Tree.Node node = second.scan(root.resolve(world), world);
                    if (node != null) {
                        worlds.put(world, node);
                    }
                }
            } finally {
                pause.resume();
                offMillis = (System.nanoTime() - offStart) / 1_000_000;
            }

            // 5. Everything else, with plugins that asked for it kept quiet while read.
            progress.accept("reading plugins and server files");
            List<String> includes = new ArrayList<>(plan.worlds());
            includes.addAll(plan.others());
            Selection selection = new Selection(includes, plan.excludes(), plan.skip());
            Map<String, Tree.Node> ready = new HashMap<>(worlds);
            Scanner rest = new Scanner(session, previous, true, selection, ready, plan.throttle(), databases,
                plan.temp(), cancelled);
            for (Map.Entry<String, Quiet> quiet : plan.quiet().entrySet()) {
                String path = quiet.getKey();
                if (selection.choose(path, root.resolve(path)) != Selection.Choice.INCLUDE || ready.containsKey(path)) {
                    continue;
                }
                quiet.getValue().pause();
                try {
                    Tree.Node node = rest.scan(root.resolve(path), path);
                    if (node != null) {
                        ready.put(path, node);
                    }
                } finally {
                    quiet.getValue().resume();
                }
            }
            Tree.Node top = rest.scanRoot(root);
            if (top == null) {
                throw new IOException("Couldn't read the server folder " + root + ".");
            }

            // 6. Store the last packs, read them back, and only then write the snapshot.
            progress.accept("checking what was written");
            session.flush();
            Set<Hash> written = new HashSet<>(session.packsWritten());
            if (!written.isEmpty()) {
                Maintenance.Report report = new Maintenance(repository, cancelled).check(0, written, new Random());
                if (!report.ok()) {
                    throw new IOException("What was just written doesn't read back correctly: "
                        + report.problems().get(0) + " The backup was not saved.");
                }
            }
            List<String> warnings = new ArrayList<>(second.warnings);
            warnings.addAll(rest.warnings);
            Snapshot snapshot = session.commit(new Snapshot(null, start, Instant.now(), top.ref(),
                parent == null ? null : parent.id(), plan.reason(), second.files + rest.files, second.bytes + rest.bytes,
                session.addedBytes(),
                pauseMillis, offMillis, plan.source(), warnings));
            return new Result(snapshot, second.chunksRead + first.chunksRead, second.chunksReused, pass1Millis);
        } finally {
            if (Files.isDirectory(plan.temp())) {
                try (var files = Files.list(plan.temp())) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        if (file.getFileName().toString().startsWith("db-")) {
                            Files.deleteIfExists(file);
                        }
                    }
                }
            }
        }
    }
}
