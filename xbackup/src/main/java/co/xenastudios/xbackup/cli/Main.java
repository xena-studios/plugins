package co.xenastudios.xbackup.cli;

import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.remote.Fetch;
import co.xenastudios.xbackup.remote.Remotes;
import co.xenastudios.xbackup.remote.RestoreSettings;
import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.restore.Swap;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Sizes;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The command-line side of xBackup, for when the server is stopped (or won't start):
 *
 * <pre>
 * java -jar plugins/xBackup-1.0.0.jar list
 * java -jar plugins/xBackup-1.0.0.jar restore latest
 * java -jar plugins/xBackup-1.0.0.jar restore 3f9a1c2e world --dry-run
 * java -jar plugins/xBackup-1.0.0.jar restore latest --remote offsite
 * java -jar plugins/xBackup-1.0.0.jar verify --full
 * </pre>
 *
 * <p>Touches no server classes: it runs on a plain JVM, Java 21 or newer.
 */
public final class Main {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PrintStream out;
    private final BufferedReader in;
    private Path server;
    private Path repositoryFolder;
    private String remote;
    private Path to;
    private boolean dryRun;
    private boolean yes;
    private boolean full;
    private final List<String> arguments = new ArrayList<>();

    Main(PrintStream out, BufferedReader in) {
        this.out = out;
        this.in = in;
    }

    public static void main(String[] args) {
        Main main = new Main(System.out, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        System.exit(main.run(args));
    }

    /** Runs a command; the exit code is 0 on success. */
    int run(String[] args) {
        try {
            if (!parse(args)) {
                return 2;
            }
            if (arguments.isEmpty() || arguments.get(0).equals("help")) {
                help();
                return arguments.isEmpty() ? 2 : 0;
            }
            String command = arguments.remove(0);
            return switch (command) {
                case "list" -> list();
                case "restore" -> restore();
                case "verify" -> verify();
                default -> {
                    out.println("Unknown command \"" + command + "\".");
                    help();
                    yield 2;
                }
            };
        } catch (Repository.LockedException e) {
            out.println(e.getMessage() + " Stop the server first, or wait for the backup to finish.");
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            out.println("Couldn't do that: " + e.getMessage());
            return 1;
        }
    }

    private boolean parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--server", "--repo", "--remote", "--to" -> {
                    if (i + 1 >= args.length) {
                        out.println(arg + " needs a value.");
                        return false;
                    }
                    String value = args[++i];
                    switch (arg) {
                        case "--server" -> server = Path.of(value).toAbsolutePath().normalize();
                        case "--repo" -> repositoryFolder = Path.of(value).toAbsolutePath().normalize();
                        case "--remote" -> remote = value;
                        default -> to = Path.of(value).toAbsolutePath().normalize();
                    }
                }
                case "--dry-run", "-n" -> dryRun = true;
                case "--yes", "-y" -> yes = true;
                case "--full" -> full = true;
                case "--help", "-h" -> arguments.add(0, "help");
                default -> {
                    if (arg.startsWith("--")) {
                        out.println("Unknown option " + arg + ".");
                        return false;
                    }
                    arguments.add(arg);
                }
            }
        }
        if (server == null) {
            server = guessServer();
        }
        return true;
    }

    // The jar sits in <server>/plugins/, normally; otherwise the current folder.
    private static Path guessServer() {
        try {
            Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path folder = jar.getParent();
            if (folder != null && folder.getFileName() != null && folder.getFileName().toString().equals("plugins")
                && folder.getParent() != null) {
                return folder.getParent();
            }
        } catch (Exception e) {
            // Fall through.
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private void help() {
        out.println("""
            xBackup's command-line restore. Run it with the server stopped.

              java -jar xBackup.jar list                          list the backups
              java -jar xBackup.jar restore <id|latest> [path...] restore everything, or only these paths
              java -jar xBackup.jar verify [--full]               check the stored data

            Options:
              --dry-run         show what a restore would change, and change nothing
              --yes             don't ask before restoring
              --to <folder>     restore into another folder instead (the server can keep running)
              --remote <name>   read from a remote in config.yml instead of the local repository
              --server <folder> the server folder (default: the one this jar's plugins folder is in)
              --repo <folder>   the repository folder (default: from xBackup's config)

            Paths are relative to the server folder, such as world, world_nether or
            plugins/xRewind. What's there now is moved to plugins/xBackup/restore/replaced/.""");
    }

    private Path home() {
        return server.resolve("plugins").resolve("xBackup");
    }

    // ---------------------------------------------------------------- opening

    private Repository open(Path cache) throws IOException {
        RestoreSettings settings = RestoreSettings.read(home());
        if (remote != null) {
            if (settings == null || !settings.remotes().containsKey(remote)) {
                throw new IllegalArgumentException("there's no remote named " + remote + " in "
                    + home().resolve(RestoreSettings.NAME) + ". Start the server once with it in config.yml.");
            }
            XBConfig.Remote remoteSettings = settings.remotes().get(remote);
            Backend backend = Remotes.open(remote, remoteSettings, new Remotes.Context(home(), Throttle.NONE,
                Logger.getLogger("xBackup"), type -> null));
            out.println("Reading " + backend.describe() + "...");
            return Fetch.metadata(backend, cache, out::println);
        }
        Path folder = repositoryFolder;
        if (folder == null) {
            folder = settings != null && settings.repository() != null ? settings.repository()
                : home().resolve("repository");
        }
        LocalBackend backend = new LocalBackend(folder);
        if (!Repository.exists(backend)) {
            throw new IllegalArgumentException("there's no xBackup repository in " + folder + ". Use --repo <folder>.");
        }
        return Repository.open(backend, home().resolve("temp"), false);
    }

    private Backend remoteBackend() throws IOException {
        RestoreSettings settings = RestoreSettings.read(home());
        return Remotes.open(remote, settings.remotes().get(remote), new Remotes.Context(home(), Throttle.NONE,
            Logger.getLogger("xBackup"), type -> null));
    }

    // ---------------------------------------------------------------- commands

    private int list() throws IOException {
        Path cache = home().resolve("temp").resolve("remote-" + (remote == null ? "" : remote));
        try (Repository repository = open(cache); Repository.Lock lock = repository.lock(false)) {
            List<String> problems = new ArrayList<>();
            List<Snapshot> snapshots = repository.snapshots(problems);
            Set<co.xenastudios.xbackup.store.Hash> pins = repository.pins();
            out.println(snapshots.size() + " backups in " + repository.backend().describe() + ":");
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                Snapshot snapshot = snapshots.get(i);
                out.printf("  %s  %s  %-12s %8s  +%s%s%n", snapshot.id().shortHex(), TIME.format(snapshot.time()),
                    snapshot.reason(), Sizes.format(snapshot.bytes()), Sizes.format(snapshot.added()),
                    pins.contains(snapshot.id()) ? "  pinned" : "");
            }
            problems.forEach(problem -> out.println("  ! " + problem));
            return 0;
        } finally {
            if (remote != null) {
                Swap.deleteTree(cache);
            }
        }
    }

    private int verify() throws IOException {
        if (remote != null) {
            throw new IllegalArgumentException("verify reads the local repository; copy it back first, or check the"
                + " remote with its own tools.");
        }
        try (Repository repository = open(null); Repository.Lock lock = repository.lock(false)) {
            out.println("Checking " + (full ? "everything" : "the structure and a 10% sample") + " in "
                + repository.backend().describe() + "...");
            Maintenance.Report report = new Maintenance(repository, () -> false).check(full ? 1.0 : 0.1, null, new Random());
            out.println("Read back " + report.packsChecked() + " of " + report.packsTotal() + " packs ("
                + Sizes.format(report.bytesChecked()) + ") across " + report.snapshots() + " backups.");
            if (report.ok()) {
                out.println("All good.");
                return 0;
            }
            report.problems().forEach(problem -> out.println("  ! " + problem));
            return 1;
        }
    }

    private int restore() throws IOException {
        if (arguments.isEmpty()) {
            out.println("Which backup? Give its id (from list) or latest.");
            return 2;
        }
        String id = arguments.remove(0);
        List<String> paths = new ArrayList<>();
        for (String path : arguments) {
            paths.add(Restore.normalise(path));
        }
        if (paths.isEmpty()) {
            paths.add("");
        }
        boolean inPlace = to == null;
        if (inPlace && !dryRun) {
            String running = runningWorld();
            if (running != null) {
                out.println("The server is running (" + running + " is locked). Stop it first, or restore with"
                    + " /backup restore in game, or into another folder with --to.");
                return 1;
            }
        }
        Path cache = home().resolve("temp").resolve("remote-" + (remote == null ? "" : remote));
        try (Repository repository = open(cache); Repository.Lock lock = repository.lock(false)) {
            Snapshot snapshot = find(repository, id);
            Set<String> protect = new HashSet<>();
            protect.add("plugins/xBackup");
            try {
                Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (jar.toAbsolutePath().startsWith(server)) {
                    protect.add(server.relativize(jar.toAbsolutePath()).toString().replace('\\', '/'));
                }
            } catch (Exception e) {
                // Unknown: jars in plugins are restored one by one anyway.
            }
            if (repository.backend() instanceof LocalBackend local && local.root().startsWith(server)) {
                protect.add(server.relativize(local.root()).toString().replace('\\', '/'));
            }
            List<Restore.Item> items = Restore.expand(repository, snapshot, paths, inPlace ? protect : Set.of());
            out.println("Backup " + snapshot.id().shortHex() + " from " + TIME.format(snapshot.time()) + " ("
                + snapshot.reason() + ").");
            if (inPlace) {
                Restore.Plan plan = Restore.dryRun(repository, items, server);
                out.println("Restoring " + describe(paths) + " into " + server + " changes " + plan.changed() + " files"
                    + (plan.changedChunks() > 0 ? " (" + plan.changedChunks() + " region chunks)" : "") + ", brings back "
                    + plan.added() + " and sets aside " + plan.removed() + " (" + plan.files() + " files, "
                    + Sizes.format(plan.bytes()) + " in all).");
                plan.examples().forEach(example -> out.println("  " + example));
            } else {
                out.println("Restoring " + describe(paths) + " into " + to + ".");
            }
            if (dryRun) {
                out.println("Dry run: nothing was changed.");
                return 0;
            }
            if (!yes && !confirm()) {
                out.println("Nothing was changed.");
                return 1;
            }
            if (remote != null) {
                Fetch.data(remoteBackend(), repository, items, out::println);
            }
            if (!inPlace) {
                Files.createDirectories(to);
                Restore.Written written = Restore.write(repository, items, to, Throttle.NONE, () -> false, out::println);
                out.println("Wrote " + written.files() + " files (" + Sizes.format(written.bytes())
                    + "), each checked against its backup, into " + to + ".");
                return 0;
            }
            Path restoreFolder = home().resolve("restore");
            if (Swap.pending(restoreFolder) != null) {
                out.println("A restore staged in game is waiting in " + restoreFolder + ". Start the server to finish it,"
                    + " or delete " + restoreFolder.resolve(Swap.PENDING) + " to cancel it, then try again.");
                return 1;
            }
            Path staged = restoreFolder.resolve(Swap.STAGED);
            Swap.deleteTree(staged);
            Files.createDirectories(staged);
            Restore.Written written = Restore.write(repository, items, staged, Throttle.NONE, () -> false, out::println);
            out.println("Checked " + written.files() + " files (" + Sizes.format(written.bytes()) + "). Putting them in place...");
            List<Swap.Entry> entries = new ArrayList<>();
            for (Restore.Item item : items) {
                entries.add(new Swap.Entry(item.path(), item.node().kind() == co.xenastudios.xbackup.store.Tree.Kind.SQLITE));
            }
            Swap.schedule(restoreFolder, snapshot.id().hex(), entries);
            Path aside = Swap.apply(restoreFolder, server, line -> out.println("  " + line));
            out.println("Done. What was there before is in " + aside + "; delete it once you're happy.");
            return 0;
        } finally {
            if (remote != null) {
                Swap.deleteTree(cache);
            }
        }
    }

    private static String describe(List<String> paths) {
        return paths.equals(List.of("")) ? "everything" : String.join(", ", paths);
    }

    private boolean confirm() throws IOException {
        out.print("Type yes to restore: ");
        out.flush();
        String answer = in.readLine();
        return answer != null && answer.strip().equalsIgnoreCase("yes");
    }

    private static Snapshot find(Repository repository, String id) throws IOException {
        List<Snapshot> snapshots = repository.snapshots(new ArrayList<>());
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("the repository has no backups.");
        }
        if (id.equalsIgnoreCase("latest") || id.equalsIgnoreCase("last")) {
            return snapshots.get(snapshots.size() - 1);
        }
        Snapshot found = null;
        for (Snapshot snapshot : snapshots) {
            if (snapshot.id().hex().startsWith(id.toLowerCase(java.util.Locale.ROOT))) {
                if (found != null) {
                    throw new IllegalArgumentException("more than one backup starts with " + id + "; give more of it.");
                }
                found = snapshot;
            }
        }
        if (found == null || id.length() < 4) {
            throw new IllegalArgumentException("no backup's id starts with " + id + ". list shows them.");
        }
        return found;
    }

    /**
     * A world the server holds open, or null. Minecraft locks each world's session.lock while
     * it runs, so if one can't be locked here, the server is running.
     */
    private String runningWorld() throws IOException {
        if (!Files.isDirectory(server)) {
            return null;
        }
        List<Path> locks = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(server)) {
            for (Path child : stream) {
                Path lock = child.resolve("session.lock");
                if (Files.isRegularFile(lock)) {
                    locks.add(lock);
                }
            }
        }
        for (Path lock : locks) {
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                FileLock held = channel.tryLock();
                if (held == null) {
                    return server.relativize(lock).toString();
                }
                held.release();
            } catch (OverlappingFileLockException e) {
                return server.relativize(lock).toString();
            } catch (IOException e) {
                // Windows refuses to open a locked file at all.
                return server.relativize(lock).toString();
            }
        }
        return null;
    }
}
