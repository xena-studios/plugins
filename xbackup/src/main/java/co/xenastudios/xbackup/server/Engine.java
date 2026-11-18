package co.xenastudios.xbackup.server;

import co.xenastudios.core.text.Durations;
import co.xenastudios.xbackup.XBackup;
import co.xenastudios.xbackup.api.BackupSnapshot;
import co.xenastudios.xbackup.api.BackupStatus;
import co.xenastudios.xbackup.api.QuietMoment;
import co.xenastudios.xbackup.api.event.BackupFailedEvent;
import co.xenastudios.xbackup.api.event.BackupFinishedEvent;
import co.xenastudios.xbackup.api.event.BackupStartEvent;
import co.xenastudios.xbackup.backup.Backup;
import co.xenastudios.xbackup.backup.Databases;
import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.remote.RemoteConfig;
import co.xenastudios.xbackup.remote.Mirror;
import co.xenastudios.xbackup.remote.Remotes;
import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.restore.Swap;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Sizes;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Runs everything xBackup does, one job at a time, on its own low-priority thread: backups,
 * checks, clean-ups, copies to remotes and restore staging. Commands and the API only queue
 * jobs and read the catalogue, which this keeps in memory so they answer instantly.
 *
 * <p>The only timer is the schedule: one delayed task, set again after each backup for the
 * next one. Checks and clean-ups ride along after a scheduled backup when they're due.
 */
@SuppressWarnings("try") // Locks held for a block, never referenced inside it.
public final class Engine {

    private static final long DAY = Duration.ofDays(1).toMillis();

    private final XBackup plugin;
    private final Logger logger;
    private final Path root;
    private final Path home;
    private final ExecutorService worker;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile Settings settings;
    private volatile Map<String, RemoteConfig> remoteSettings = Map.of();

    // Worker thread only.
    private Repository repository;
    private Path repositoryPath;
    private final Properties state = new Properties();

    // The catalogue: written by the worker, read from anywhere.
    private volatile List<Snapshot> snapshots = List.of();
    private volatile Set<Hash> pins = Set.of();
    private volatile List<String> topLevel = List.of();
    private volatile long repositoryBytes;
    private volatile String activity;
    private volatile String problem;
    private volatile Instant nextBackup;
    private volatile Backup.Result lastResult;
    private final Map<String, String> remoteStatus = new ConcurrentHashMap<>();
    // True at start: what changed before a restart still needs backing up.
    private volatile boolean playersSeen = true;
    // The last scheduled run that didn't make a backup (skipped or failed), so the next is
    // counted from it, not retried every minute.
    private volatile Instant lastAttempt;

    private volatile ScheduledTask timer;
    private volatile WorldPause pause;
    private final AtomicBoolean abandoned = new AtomicBoolean();

    // From other plugins, through the API.
    final Map<Plugin, List<ApiSource>> sources = new ConcurrentHashMap<>();
    final Map<Plugin, QuietMoment> quietMoments = new ConcurrentHashMap<>();
    final Map<String, Remotes.Registered> backends = new ConcurrentHashMap<>();

    record ApiSource(Path path, List<String> excludes) {}

    public Engine(XBackup plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.root = Path.of("").toAbsolutePath().normalize();
        this.home = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xBackup worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    public Path root() {
        return root;
    }

    public Path home() {
        return home;
    }

    public Path restoreFolder() {
        return home.resolve("restore");
    }

    Path temp() {
        return home.resolve("temp");
    }

    // ---------------------------------------------------------------- config

    /**
     * Applies the config: on startup and every reload. Global thread.
     *
     * @param secrets each remote's passwords, from {@link Secrets}
     */
    public void configure(XBConfig config, Map<String, Secrets.Entry> secrets) {
        Settings next = Settings.of(config, root, warning -> logger.warning("config.yml: " + warning));
        Settings previous = settings;
        settings = next;
        Map<String, RemoteConfig> remotes = new LinkedHashMap<>();
        for (Map.Entry<String, XBConfig.Remote> remote : config.remotes().entrySet()) {
            Secrets.Entry secret = secrets.getOrDefault(remote.getKey(), new Secrets.Entry("", ""));
            remotes.put(remote.getKey(), RemoteConfig.of(remote.getValue(), secret.password(), secret.clientSecret()));
        }
        remoteSettings = Collections.unmodifiableMap(remotes);
        submit(null, () -> {
            if (previous == null) {
                Files.createDirectories(temp());
                Swap.deleteTree(temp());
                loadState();
            }
            if (repository == null || !next.repository().equals(repositoryPath)) {
                openRepository(next.repository());
            }
            CliFile.write(home, next, remotes, logger);
            return null;
        }).whenComplete((ignored, error) -> arm());
    }

    private void openRepository(Path path) throws IOException {
        if (repository != null) {
            repository.close();
            repository = null;
        }
        Files.createDirectories(path);
        LocalBackend backend = new LocalBackend(path);
        Repository opened = Repository.open(backend, temp(), true);
        try (Repository.Lock lock = opened.lock(true)) {
            backend.clearTemp();
        } catch (Repository.LockedException e) {
            // Something else has it right now (a restore from the command line?); tidy up later.
        }
        repository = opened;
        repositoryPath = path;
        refresh();
        logger.info("Repository: " + path + " (" + snapshots.size() + " backups, " + Sizes.format(repositoryBytes) + ").");
    }

    // Re-reads the catalogue. Worker thread.
    private void refresh() throws IOException {
        List<String> problems = new ArrayList<>();
        List<Snapshot> list = repository.snapshots(problems);
        for (String found : problems) {
            logger.warning(found);
        }
        snapshots = List.copyOf(list);
        pins = Set.copyOf(repository.pins());
        long bytes = 0;
        for (Backend.FileInfo file : repository.backend().list("")) {
            bytes += file.size();
        }
        repositoryBytes = bytes;
        List<String> names = new ArrayList<>();
        if (!list.isEmpty()) {
            try {
                for (co.xenastudios.xbackup.store.Tree.Node node : repository.tree(list.get(list.size() - 1).tree()).nodes()) {
                    names.add(node.name());
                }
            } catch (IOException e) {
                // Completions only.
            }
        }
        topLevel = List.copyOf(names);
    }

    // ---------------------------------------------------------------- jobs

    private <T> CompletableFuture<T> submit(String what, Callable<T> job) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (stopping.get()) {
            future.completeExceptionally(new IllegalStateException("xBackup is stopping."));
            return future;
        }
        try {
            worker.execute(() -> {
                if (what != null) {
                    activity = what;
                }
                try {
                    future.complete(job.call());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                } finally {
                    activity = null;
                    if (repository != null) {
                        repository.release();
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("xBackup is stopping."));
        }
        return future;
    }

    private Repository repository() throws IOException {
        if (repository == null) {
            openRepository(settings.repository());
        }
        return repository;
    }

    private void progress(String job, String step) {
        activity = job + ": " + step;
    }

    // ---------------------------------------------------------------- backups

    /**
     * Queues a backup.
     *
     * @return completes with the snapshot, or exceptionally with a message for people
     */
    public CompletableFuture<Snapshot> backup(String reason, boolean scheduled) {
        CompletableFuture<Snapshot> result = submit("backing up", () -> runBackup(reason, scheduled));
        result.whenComplete((snapshot, error) -> {
            if (scheduled) {
                arm();
            }
        });
        return result;
    }

    private Snapshot runBackup(String reason, boolean scheduled) throws Exception {
        if (scheduled) {
            lastAttempt = Instant.now();
            Settings now = settings;
            List<Snapshot> list = snapshots;
            // A backup made by hand since the timer was set moves the next one along.
            if (now.every() != null && !list.isEmpty()
                && list.get(list.size() - 1).time().plus(now.every()).isAfter(Instant.now().plus(Duration.ofMinutes(1)))) {
                throw new Skipped("a backup ran since this one was scheduled");
            }
        }
        if (scheduled && settings.skipWhenIdle() && !playersSeen && Bukkit.getOnlinePlayers().isEmpty()
            && !snapshots.isEmpty()) {
            logger.fine("Skipped a scheduled backup: nobody has been online since the last one.");
            throw new Skipped("nobody has been online since the last backup");
        }
        if (!fireStart(reason)) {
            throw new Skipped("a plugin cancelled it");
        }
        playersSeen = !Bukkit.getOnlinePlayers().isEmpty();
        Settings now = settings;
        Repository repo = repository();
        broadcast(plugin.config().messages().broadcastStart(), null);
        long started = System.nanoTime();
        try (Repository.Lock lock = repo.lock(true)) {
            ensureSpace(repo, now);
            Backup.Plan plan = plan(now, reason);
            abandoned.set(false);
            WorldPause worldPause = new WorldPause(plugin, plugin.isFolia(), logger, now.maxPause(), () -> abandoned.set(true));
            pause = worldPause;
            Backup.Result result;
            try {
                result = Backup.run(repo, plan, worldPause, Databases.sqlite(),
                    () -> stopping.get() || abandoned.get(), step -> progress("backing up", step));
            } finally {
                worldPause.resume();
                pause = null;
            }
            lastResult = result;
            Snapshot snapshot = result.snapshot();
            problem = null;
            logger.info("Backed up " + snapshot.files() + " files (" + Sizes.format(snapshot.bytes()) + ") in "
                + seconds(snapshot.durationMillis()) + ": " + Sizes.format(snapshot.added()) + " new. The server paused "
                + snapshot.pauseMillis() + " ms.");
            for (String warning : snapshot.warnings()) {
                logger.warning("Backup " + snapshot.id().shortHex() + ": " + warning);
            }
            upkeep(repo, now, scheduled);
            refresh();
            fireFinished(snapshot);
            broadcast(plugin.config().messages().broadcastDone(), Durations.format(Duration.ofNanos(System.nanoTime() - started)));
        } catch (Skipped e) {
            throw e;
        } catch (Exception e) {
            String message = abandoned.get()
                ? "Reading the worlds took longer than performance.max-pause; autosave went back on and this backup was abandoned."
                : describe(e);
            problem = message;
            logger.log(Level.WARNING, "Backup failed: " + message, e instanceof IOException ? null : e);
            fireFailed(reason, message);
            try {
                refresh();
            } catch (IOException ignored) {
                // The failure above says enough.
            }
            throw new IOException(message, e);
        }
        Snapshot latest = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        syncRemotes();
        return latest;
    }

    /** A backup that didn't run, on purpose. */
    public static final class Skipped extends IOException {
        Skipped(String reason) {
            super(reason);
        }
    }

    private Backup.Plan plan(Settings now, String reason) throws Exception {
        List<Path> worldFolders = MainThread.call(plugin, plugin.isFolia(), 60, Sources::worldFolders);
        List<Path> extra = new ArrayList<>();
        List<String> excludes = new ArrayList<>(now.excludes());
        for (Map.Entry<Plugin, List<ApiSource>> entry : sources.entrySet()) {
            if (!entry.getKey().isEnabled()) {
                sources.remove(entry.getKey());
                continue;
            }
            for (ApiSource source : entry.getValue()) {
                extra.add(source.path());
                excludes.addAll(scoped(Sources.relative(root, source.path()), source.excludes()));
            }
        }
        Sources.Resolved resolved = Sources.resolve(root, now.sources(), worldFolders,
            plugin.getServer().getPluginsFolder().toPath(), extra, logger);
        Set<Path> skip = new HashSet<>();
        skip.add(now.repository());
        skip.add(restoreFolder());
        skip.add(temp());
        skip.add(home.resolve("libraries"));
        // Secrets never go into a backup, which is copied to every remote.
        skip.add(home.resolve(CliFile.NAME));
        skip.add(home.resolve(Remotes.TOKENS));
        skip.add(home.resolve(Secrets.NAME));
        skip.add(home.resolve(co.xenastudios.xbackup.restore.ServerCheck.MARKER));
        for (RemoteConfig remote : remoteSettings.values()) {
            if (!remote.privateKey().isBlank()) {
                skip.add(home.resolve(remote.privateKey().strip()));
            }
        }
        Map<String, Backup.Quiet> quiet = new LinkedHashMap<>();
        for (Map.Entry<Plugin, QuietMoment> entry : quietMoments.entrySet()) {
            if (!entry.getKey().isEnabled()) {
                quietMoments.remove(entry.getKey());
                continue;
            }
            String path = Sources.relative(root, entry.getKey().getDataFolder().toPath());
            if (path != null) {
                QuietMoment moment = entry.getValue();
                quiet.put(path, new Backup.Quiet() {
                    @Override
                    public void pause() throws Exception {
                        moment.pause();
                    }

                    @Override
                    public void resume() {
                        moment.resume();
                    }
                });
            }
        }
        return new Backup.Plan(root, resolved.worlds(), resolved.others(), excludes, skip, quiet, reason,
            plugin.getName() + " " + plugin.getPluginMeta().getVersion(), temp(), new Throttle(now.readLimit()),
            new Session.Options(now.level(), 16L << 20, Math.max(now.minFree(), 64L << 20)));
    }

    /**
     * A plugin's excludes for its own source, made to apply only inside it: a pattern that
     * starts with the source's path stays as it is, and any other is read from the source,
     * so {@code **}{@code /*.log} leaves out that plugin's logs, never every log on the server.
     */
    static List<String> scoped(String source, List<String> excludes) {
        List<String> scoped = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return scoped;
        }
        for (String pattern : excludes) {
            String clean = pattern.strip().replace('\\', '/');
            while (clean.startsWith("/")) {
                clean = clean.substring(1);
            }
            if (clean.isEmpty()) {
                continue;
            }
            scoped.add(clean.equals(source) || clean.startsWith(source + "/") ? clean : source + "/" + clean);
        }
        return scoped;
    }

    // Never start a backup that can't finish: make room first if the disk is short.
    private void ensureSpace(Repository repo, Settings now) throws IOException {
        long free = Files.getFileStore(now.repository()).getUsableSpace();
        long last = lastResult != null ? lastResult.snapshot().added() : 0;
        long needed = now.minFree() + Math.max(256L << 20, last * 2);
        if (free >= needed) {
            return;
        }
        logger.info("Only " + Sizes.format(free) + " free; removing old backups to make room before backing up.");
        Maintenance maintenance = new Maintenance(repo, stopping::get);
        maintenance.forget(now.retention(), now.maxSize(), ZoneId.systemDefault());
        maintenance.collect(new Session.Options(now.level(), 16L << 20, 0), 0.3);
        state.setProperty("last-collect", Long.toString(System.currentTimeMillis()));
        saveState();
        free = Files.getFileStore(now.repository()).getUsableSpace();
        if (free < needed) {
            throw new Session.NoSpaceException("Not enough free space to back up: " + Sizes.format(free) + " free in "
                + now.repository() + ", and retention.min-free-space (" + Sizes.format(now.minFree())
                + ") plus room for the backup is " + Sizes.format(needed) + ". Free some space, lower retention, or"
                + " move the repository to a bigger disk.");
        }
    }

    // After a backup: forget what retention no longer keeps, collect garbage once a day (or
    // when over the size limit), and check older data when that's due.
    private void upkeep(Repository repo, Settings now, boolean scheduled) throws IOException {
        progress("backing up", "applying retention");
        Maintenance maintenance = new Maintenance(repo, stopping::get);
        Maintenance.Forgotten forgotten = maintenance.forget(now.retention(), now.maxSize(), ZoneId.systemDefault());
        long lastCollect = Long.parseLong(state.getProperty("last-collect", "0"));
        boolean overCap = now.maxSize() > 0 && repositoryBytes > now.maxSize();
        if (!forgotten.removed().isEmpty() && (System.currentTimeMillis() - lastCollect > DAY || overCap)) {
            progress("backing up", "giving back space");
            Maintenance.Collected collected = maintenance.collect(new Session.Options(now.level(), 16L << 20, 0), 0.3);
            state.setProperty("last-collect", Long.toString(System.currentTimeMillis()));
            saveState();
            if (collected.bytesFreed() > 0) {
                logger.info("Gave back " + Sizes.format(collected.bytesFreed()) + " from old backups.");
            }
        }
        long lastVerify = Long.parseLong(state.getProperty("last-verify", "0"));
        if (scheduled && now.verifyEvery() != null && System.currentTimeMillis() - lastVerify > now.verifyEvery().toMillis()) {
            progress("backing up", "checking older data");
            Maintenance.Report report = maintenance.check(now.verifyPercent() / 100.0, null, new Random());
            recordCheck(report);
        }
    }

    private void recordCheck(Maintenance.Report report) {
        state.setProperty("last-verify", Long.toString(System.currentTimeMillis()));
        state.setProperty("last-verify-result", report.ok() ? "ok"
            : report.problems().size() + " problems: " + report.problems().get(0));
        saveState();
        if (report.ok()) {
            logger.info("Checked " + report.packsChecked() + " of " + report.packsTotal() + " packs ("
                + Sizes.format(report.bytesChecked()) + "): all good.");
        } else {
            problem = "The last check found " + report.problems().size() + " problems. /backup verify full for details.";
            for (String found : report.problems()) {
                logger.warning("Check: " + found);
            }
        }
    }

    // ---------------------------------------------------------------- the schedule

    /** Sets the timer for the next scheduled backup. */
    public void arm() {
        ScheduledTask old = timer;
        if (old != null) {
            old.cancel();
            timer = null;
        }
        Settings now = settings;
        if (stopping.get() || now == null || now.every() == null || !plugin.isEnabled()) {
            nextBackup = null;
            return;
        }
        List<Snapshot> list = snapshots;
        Instant last = list.isEmpty() ? null : list.get(list.size() - 1).time();
        Instant attempt = lastAttempt;
        if (attempt != null && (last == null || attempt.isAfter(last))) {
            last = attempt;
        }
        Instant due = last == null ? Instant.now().plus(Duration.ofMinutes(1)) : last.plus(now.every());
        // Never sooner than a minute from now: let the server settle after a start.
        Instant earliest = Instant.now().plus(Duration.ofMinutes(1));
        if (due.isBefore(earliest)) {
            due = earliest;
        }
        nextBackup = due;
        long delay = Math.max(1, Duration.between(Instant.now(), due).toMillis());
        timer = Bukkit.getAsyncScheduler().runDelayed(plugin, task -> backup("scheduled", true)
            .exceptionally(error -> null), delay, TimeUnit.MILLISECONDS);
    }

    /** A player joined: a scheduled backup won't be skipped as idle. */
    public void playerSeen() {
        playersSeen = true;
    }

    // ---------------------------------------------------------------- other jobs

    /** Checks the repository: a share of the packs, or all of them. */
    public CompletableFuture<Maintenance.Report> verify(boolean full) {
        return submit(full ? "checking everything" : "checking", () -> {
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(false)) {
                Maintenance.Report report = new Maintenance(repo, stopping::get)
                    .check(full ? 1.0 : settings.verifyPercent() / 100.0, null, new Random());
                recordCheck(report);
                return report;
            }
        });
    }

    /** What {@link #prune} did. */
    public record Pruned(int removed, int kept, long freed) {}

    /** Applies retention and gives back the space now. */
    public CompletableFuture<Pruned> prune() {
        return submit("cleaning up", () -> {
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(true)) {
                Settings now = settings;
                Maintenance maintenance = new Maintenance(repo, stopping::get);
                Maintenance.Forgotten forgotten = maintenance.forget(now.retention(), now.maxSize(), ZoneId.systemDefault());
                Maintenance.Collected collected = maintenance.collect(new Session.Options(now.level(), 16L << 20, 0), 0.3);
                state.setProperty("last-collect", Long.toString(System.currentTimeMillis()));
                saveState();
                refresh();
                return new Pruned(forgotten.removed().size(), forgotten.kept(), collected.bytesFreed());
            }
        });
    }

    /** Rebuilds the index from the packs. */
    public CompletableFuture<Maintenance.Rebuilt> repair() {
        return submit("repairing", () -> {
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(true)) {
                Maintenance.Rebuilt rebuilt = new Maintenance(repo, stopping::get).rebuildIndex();
                refresh();
                return rebuilt;
            }
        });
    }

    /** Pins or unpins a snapshot. */
    public CompletableFuture<Snapshot> pin(Snapshot snapshot, boolean pinned) {
        return submit("pinning", () -> {
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(true)) {
                repo.pin(snapshot.id(), pinned);
                refresh();
                return snapshot;
            }
        });
    }

    /** Removes one snapshot now. Its space comes back at the next clean-up. */
    public CompletableFuture<Snapshot> forget(Snapshot snapshot) {
        return submit("removing a backup", () -> {
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(true)) {
                repo.deleteSnapshot(snapshot);
                refresh();
                return snapshot;
            }
        });
    }

    // ---------------------------------------------------------------- restore

    /** Paths that are never restored over: xBackup's own folder and jar, and the repository. */
    public Set<String> protectedPaths() {
        Set<String> protect = new HashSet<>();
        String own = Sources.relative(root, home);
        if (own != null) {
            protect.add(own);
        }
        String repo = Sources.relative(root, settings.repository());
        if (repo != null && !repo.isEmpty()) {
            protect.add(repo);
        }
        try {
            Path jar = Path.of(XBackup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String relative = Sources.relative(root, jar);
            if (relative != null) {
                protect.add(relative);
            }
        } catch (Exception e) {
            // Unknown jar location: the plugins folder's jars are still restored one by one.
        }
        return protect;
    }

    /** What restoring the paths from a snapshot would change. */
    public CompletableFuture<Restore.Plan> plan(Snapshot snapshot, List<String> paths) {
        return submit("planning a restore", () -> {
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(false)) {
                List<Restore.Item> items = Restore.expand(repo, snapshot, paths, protectedPaths());
                return Restore.dryRun(repo, items, root);
            }
        });
    }

    /**
     * Writes the paths from a snapshot into the restore folder, checked, and schedules them to
     * be swapped in on the next start, before any world loads.
     */
    /**
     * A staged restore.
     *
     * @param jars plugin jars in it: Paper has loaded every plugin's jar before xBackup can swap
     *     files in, so those take effect only from the start after
     */
    public record Staged(Restore.Written written, List<String> jars) {}

    public CompletableFuture<Staged> stage(Snapshot snapshot, List<String> paths) {
        return submit("staging a restore", () -> {
            if (Swap.pending(restoreFolder()) != null) {
                throw new IOException("A restore is already waiting for the next start. /backup restore cancel first.");
            }
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(false)) {
                List<Restore.Item> items = Restore.expand(repo, snapshot, paths, protectedPaths());
                Restore.Plan plan = Restore.dryRun(repo, items, root);
                Path staged = restoreFolder().resolve(Swap.STAGED);
                Swap.deleteTree(staged);
                Files.createDirectories(staged);
                long free = Files.getFileStore(staged).getUsableSpace();
                if (free < plan.bytes() + settings.minFree()) {
                    throw new Session.NoSpaceException("Restoring that needs " + Sizes.format(plan.bytes()) + " and only "
                        + Sizes.format(free) + " is free (keeping retention.min-free-space).");
                }
                Restore.Written written;
                try {
                    written = Restore.write(repo, items, staged, root, false, new Throttle(settings.readLimit()), stopping::get,
                        step -> progress("staging a restore", step));
                } catch (IOException e) {
                    Swap.deleteTree(staged);
                    throw e;
                }
                List<Swap.Entry> entries = new ArrayList<>();
                for (Restore.Item item : items) {
                    entries.add(new Swap.Entry(item.path(), item.node().kind() == co.xenastudios.xbackup.store.Tree.Kind.SQLITE));
                }
                Swap.schedule(restoreFolder(), snapshot.id().hex(), entries);
                List<String> jars = new ArrayList<>();
                for (Restore.Item item : items) {
                    if (item.path().endsWith(".jar") && item.node().kind() != co.xenastudios.xbackup.store.Tree.Kind.DIR) {
                        jars.add(item.path());
                    } else if (item.node().kind() == co.xenastudios.xbackup.store.Tree.Kind.DIR) {
                        for (co.xenastudios.xbackup.store.Tree.Node child : repo.tree(item.node().ref()).nodes()) {
                            if (child.name().endsWith(".jar") && child.kind() != co.xenastudios.xbackup.store.Tree.Kind.DIR) {
                                jars.add(item.path() + "/" + child.name());
                            }
                        }
                    }
                }
                return new Staged(written, jars);
            }
        });
    }

    /** Cancels a restore waiting for the next start. */
    public CompletableFuture<Boolean> cancelRestore() {
        return submit("cancelling a restore", () -> Swap.cancel(restoreFolder()));
    }

    /** The restore waiting for the next start, or null. Worker-free: reads one small file. */
    public CompletableFuture<Swap.Pending> pendingRestore() {
        return submit(null, () -> Swap.pending(restoreFolder()));
    }

    // ---------------------------------------------------------------- remotes

    // After a backup, under the shared lock so a command-line restore or check can run beside
    // it but nothing deletes from the repository meanwhile.
    private void syncRemotes() {
        Map<String, RemoteConfig> remotes = remoteSettings;
        if (remotes.values().stream().noneMatch(RemoteConfig::enabled)) {
            return;
        }
        try (Repository.Lock lock = repository.lock(false)) {
            for (Map.Entry<String, RemoteConfig> entry : remotes.entrySet()) {
                if (!entry.getValue().enabled() || stopping.get()) {
                    continue;
                }
                String name = entry.getKey();
                progress("copying", name);
                long start = System.nanoTime();
                try (Backend remote = Remotes.open(name, entry.getValue(), remotesContext())) {
                    Mirror.Result result = Mirror.push(repository, remote, stopping::get,
                        step -> progress("copying to " + name, step));
                    remoteStatus.put(name, "up to date (" + Sizes.format(result.bytesSent()) + " sent in "
                        + seconds((System.nanoTime() - start) / 1_000_000) + ", " + java.time.LocalTime.now().withNano(0) + ")"
                        + (result.held() == null ? "" : "; kept some files: see the console"));
                    if (result.filesSent() > 0 || result.filesDeleted() > 0) {
                        logger.info("Copied to " + name + ": " + result.filesSent() + " files (" + Sizes.format(result.bytesSent())
                            + ") sent, " + result.filesDeleted() + " removed.");
                    }
                    if (result.held() != null) {
                        logger.warning("Copying to " + name + ": " + result.held() + ".");
                    }
                } catch (Exception e) {
                    String message = describe(e);
                    remoteStatus.put(name, "failed: " + message);
                    logger.warning("Couldn't copy the backups to " + name + ": " + message);
                }
            }
        } catch (IOException e) {
            logger.warning("Couldn't copy the backups to the remotes: " + describe(e));
        }
    }

    /** Copies the repository to one remote now. */
    public CompletableFuture<Mirror.Result> push(String name) {
        return submit("copying to " + name, () -> {
            RemoteConfig settings = remoteSettings.get(name);
            if (settings == null) {
                throw new IOException("There's no remote named " + name + " in config.yml.");
            }
            Repository repo = repository();
            try (Repository.Lock lock = repo.lock(false); Backend remote = Remotes.open(name, settings, remotesContext())) {
                Mirror.Result result = Mirror.push(repo, remote, stopping::get,
                    step -> progress("copying to " + name, step));
                remoteStatus.put(name, "up to date (" + java.time.LocalTime.now().withNano(0) + ")");
                if (result.held() != null) {
                    logger.warning("Copying to " + name + ": " + result.held() + ".");
                }
                return result;
            } catch (Exception e) {
                remoteStatus.put(name, "failed: " + describe(e));
                throw e;
            }
        });
    }

    /**
     * Links a cloud drive remote. Google Drive and OneDrive: {@code say} gets the address and
     * code to enter, then word once it's linked; the wait runs on its own thread, not the
     * worker, so backups carry on. Dropbox: without a code, {@code say} gets the link to open;
     * with the code Dropbox showed, it's swapped for a token.
     */
    public void link(String name, String code, java.util.function.Consumer<String> say) {
        RemoteConfig remote = remoteSettings.get(name);
        if (remote == null) {
            throw new IllegalArgumentException("There's no remote named " + name + " in config.yml.");
        }
        String type = remote.type().strip().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("drive", "onedrive", "dropbox").contains(type)) {
            throw new IllegalArgumentException(name + " is a " + type + " remote; only drive, onedrive and dropbox are linked.");
        }
        String shown = text(name);
        plugin.tasks().async(() -> {
            try {
                if (type.equals("dropbox")) {
                    if (code == null) {
                        say.accept("Open " + link(co.xenastudios.xbackup.remote.OAuth.dropboxUrl(home, name, remote), "this link")
                            + ", allow xBackup, then run <highlight>/backup link " + shown + " \\<code></highlight> with the"
                            + " code Dropbox shows.");
                        return;
                    }
                    co.xenastudios.xbackup.remote.OAuth.dropboxCode(home, name, remote, code);
                } else {
                    co.xenastudios.xbackup.remote.OAuth.DeviceCode device =
                        co.xenastudios.xbackup.remote.OAuth.start(name, type, remote);
                    say.accept("Go to " + link(device.url(), device.url()) + " on any device and enter <highlight>"
                        + text(device.userCode()) + "</highlight>. Waiting...");
                    co.xenastudios.xbackup.remote.OAuth.finish(home, name, type, remote, device, stopping::get);
                }
                say.accept("<success>Linked</success> " + shown + ". Backups are copied to it from the next one; "
                    + "<highlight>/backup copy " + shown + "</highlight> copies them now.");
            } catch (IOException | RuntimeException e) {
                say.accept("<error>Couldn't link " + shown + ": " + text(describe(e)) + "</error>");
            }
        });
    }

    // What a provider sent, as plain text in a MiniMessage line: never tags of its own.
    private static String text(String value) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(value == null ? "" : value);
    }

    // A clickable link to a provider's https address; anything else is shown as text only.
    private static String link(String url, String label) {
        if (url == null || !url.matches("https://[^\\s'\\\\<>]+")) {
            return text(url);
        }
        return "<click:open_url:'" + url + "'><highlight><underlined>" + text(label) + "</underlined></highlight></click>";
    }

    public Remotes.Context remotesContext() {
        return new Remotes.Context(home, new Throttle(settings.uploadLimit()), logger, backends::get);
    }

    public Map<String, RemoteConfig> remotes() {
        return remoteSettings;
    }

    public Map<String, String> remoteStatus() {
        return Collections.unmodifiableMap(remoteStatus);
    }

    // ---------------------------------------------------------------- reading the catalogue

    public List<Snapshot> snapshots() {
        return snapshots;
    }

    public boolean pinned(Snapshot snapshot) {
        return pins.contains(snapshot.id());
    }

    /**
     * Finds a snapshot by id prefix or {@code latest}.
     *
     * @throws IllegalArgumentException with a message for people
     */
    public Snapshot find(String text) {
        List<Snapshot> list = snapshots;
        if (list.isEmpty()) {
            throw new IllegalArgumentException("There are no backups yet.");
        }
        String query = text.strip().toLowerCase(java.util.Locale.ROOT);
        if (query.equals("latest") || query.equals("last")) {
            return list.get(list.size() - 1);
        }
        if (query.length() < 4) {
            throw new IllegalArgumentException("Give at least 4 characters of the backup's id, or latest.");
        }
        Snapshot found = null;
        for (Snapshot snapshot : list) {
            if (snapshot.id().hex().startsWith(query)) {
                if (found != null) {
                    throw new IllegalArgumentException("More than one backup starts with " + query + "; give more of it.");
                }
                found = snapshot;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("No backup's id starts with " + query + ". /backup list shows them.");
        }
        return found;
    }

    public String activity() {
        return activity;
    }

    public String problem() {
        return problem;
    }

    public Instant nextBackup() {
        return nextBackup;
    }

    public long repositoryBytes() {
        return repositoryBytes;
    }

    public Backup.Result lastResult() {
        return lastResult;
    }

    public Settings settings() {
        return settings;
    }

    public String lastCheck() {
        String when = state.getProperty("last-verify");
        if (when == null) {
            return "never";
        }
        Duration ago = Duration.ofMillis(System.currentTimeMillis() - Long.parseLong(when));
        return Durations.format(ago) + " ago, " + state.getProperty("last-verify-result", "?");
    }

    public BackupSnapshot toApi(Snapshot snapshot) {
        return new BackupSnapshot(snapshot.id().hex(), snapshot.time(), Duration.ofMillis(snapshot.durationMillis()),
            snapshot.reason(), snapshot.files(), snapshot.bytes(), snapshot.added(), Duration.ofMillis(snapshot.pauseMillis()),
            pinned(snapshot), snapshot.warnings());
    }

    public BackupStatus status() {
        List<Snapshot> list = snapshots;
        return new BackupStatus(activity, list.isEmpty() ? null : toApi(list.get(list.size() - 1)), nextBackup,
            list.size(), repositoryBytes, problem);
    }

    // ---------------------------------------------------------------- events

    private boolean fireStart(String reason) throws Exception {
        if (BackupStartEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return true;
        }
        return MainThread.call(plugin, plugin.isFolia(), 30, () -> {
            BackupStartEvent event = new BackupStartEvent(reason);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        });
    }

    private void fireFinished(Snapshot snapshot) {
        if (BackupFinishedEvent.getHandlerList().getRegisteredListeners().length > 0) {
            Bukkit.getPluginManager().callEvent(new BackupFinishedEvent(toApi(snapshot)));
        }
    }

    private void fireFailed(String reason, String message) {
        if (BackupFailedEvent.getHandlerList().getRegisteredListeners().length > 0) {
            Bukkit.getPluginManager().callEvent(new BackupFailedEvent(reason, message));
        }
    }

    private void broadcast(co.xenastudios.core.text.Message message, String time) {
        if (message.isEmpty()) {
            return;
        }
        MainThread.run(plugin, plugin.isFolia(), () -> Bukkit.broadcast(time == null ? message.chat()
            : message.chat(co.xenastudios.core.text.Placeholders.text("time", time))));
    }

    // ---------------------------------------------------------------- state

    private Path stateFile() {
        return home.resolve("state.properties");
    }

    private void loadState() {
        try {
            if (Files.exists(stateFile())) {
                state.load(new StringReader(Files.readString(stateFile(), StandardCharsets.UTF_8)));
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warning("Couldn't read " + stateFile() + "; starting it again: " + e.getMessage());
        }
    }

    private void saveState() {
        try {
            StringWriter writer = new StringWriter();
            state.store(writer, "xBackup's own bookkeeping: when it last checked and cleaned up. Safe to delete.");
            Path temp = home.resolve("state.properties.part");
            Files.writeString(temp, writer.toString(), StandardCharsets.UTF_8);
            Files.move(temp, stateFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warning("Couldn't save " + stateFile() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- stopping

    /** Stops everything: autosave back on now, the running job told to stop, the worker given a few seconds. */
    public void stop() {
        stopping.set(true);
        ScheduledTask old = timer;
        if (old != null) {
            old.cancel();
        }
        WorldPause current = pause;
        if (current != null) {
            current.resumeNow();
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("A backup job didn't stop within 10 seconds; it's abandoned. Nothing it wrote is used.");
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (repository != null) {
            try {
                repository.close();
            } catch (IOException ignored) {
                // Stopping anyway.
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** An error as people should read it. */
    public static String describe(Throwable error) {
        Throwable e = error;
        while ((e instanceof java.util.concurrent.CompletionException || e instanceof java.util.concurrent.ExecutionException)
            && e.getCause() != null) {
            e = e.getCause();
        }
        if (e instanceof java.nio.file.NoSuchFileException missing && missing.getMessage() != null
            && !missing.getMessage().contains(" ")) {
            return "a file disappeared: " + missing.getMessage();
        }
        if (e instanceof java.nio.file.AccessDeniedException denied) {
            return "access denied to " + denied.getMessage();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    static String seconds(long millis) {
        return millis < 1000 ? millis + " ms" : millis < 60_000 ? String.format("%.1fs", millis / 1000.0)
            : Durations.format(Duration.ofMillis(millis));
    }

    /** The top-level paths in the newest backup, for completing {@code /backup restore}. */
    public List<String> topLevel() {
        return topLevel;
    }
}
