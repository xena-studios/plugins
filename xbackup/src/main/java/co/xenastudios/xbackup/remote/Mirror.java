package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Pack;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Keeps a copy of a repository on another backend. Files never change, so a copy is
 * incremental by nature: anything the remote already has (same name, same size) is
 * skipped, so an interrupted copy picks up where it stopped. Packs go first, then index
 * files, then snapshots, so a remote never lists a snapshot whose data isn't there yet; and
 * deletions run the other way round.
 *
 * <p>Deleting is where a copy can do harm: the remote is the copy that's needed when the
 * local repository is damaged, rolled back with its disk, or restored from an old copy. So the
 * remote only loses what this repository removed on purpose:
 *
 * <ul>
 * <li>a snapshot only if the repository's {@code forgotten} list has it (retention or {@code
 *     /backup forget} removed it), and it's older than the newest local snapshot;
 * <li>packs only if every snapshot left on the remote is also here, and garbage collection
 *     here deleted the pack on purpose (its {@code collected} list); never one that went
 *     missing here ({@code lost});
 * <li>index files only if every pack left on the remote is listed by a local index file;
 * <li>nothing at all if the local repository has no snapshots while the remote has some, or
 *     if a local index lists a pack that's missing here (local damage).
 * </ul>
 */
public final class Mirror {

    private static final List<String> ORDER = List.of("config", "data/", "index/", "keep/", "snapshots/");

    private Mirror() {}

    /**
     * What a copy did.
     *
     * @param held why some deletions were held back, for the console; null if none were
     */
    public record Result(int filesSent, long bytesSent, int filesDeleted, String held) {}

    public static Result push(Repository local, Backend to, BooleanSupplier cancelled, Consumer<String> progress)
        throws IOException {
        Backend from = local.backend();
        checkSameRepository(from, to);
        Map<String, Long> source = new HashMap<>();
        for (Backend.FileInfo file : from.list("")) {
            if (rank(file.name()) >= 0) {
                source.put(file.name(), file.size());
            }
        }
        Map<String, Long> target = new HashMap<>();
        for (Backend.FileInfo file : to.list("")) {
            target.put(file.name(), file.size());
        }
        List<String> send = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Long> file : source.entrySet()) {
            if (!file.getValue().equals(target.get(file.getKey()))) {
                send.add(file.getKey());
                total += file.getValue();
            }
        }
        send.sort(Comparator.comparingInt(Mirror::rank).thenComparing(Comparator.naturalOrder()));
        int sent = 0;
        long bytes = 0;
        for (String name : send) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("stopped");
            }
            progress.accept(sent + " of " + send.size() + " files, " + percent(bytes, total));
            Path file = from.localFile(name);
            if (file != null) {
                to.put(name, file);
            } else {
                Path temp = Files.createTempFile("xbackup", ".part");
                try (InputStream in = from.get(name)) {
                    Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    to.put(name, temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            sent++;
            bytes += source.get(name);
        }
        Deletions deletions = deletions(local, source, target);
        List<String> remove = new ArrayList<>(deletions.names());
        remove.sort(Comparator.comparingInt(Mirror::rank).reversed());
        for (String name : remove) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("stopped");
            }
            to.delete(name);
        }
        return new Result(sent, bytes, remove.size(), deletions.held());
    }

    /** What a copy may delete from the remote, and why it held back the rest. */
    record Deletions(List<String> names, String held) {}

    static Deletions deletions(Repository local, Map<String, Long> source, Map<String, Long> target) throws IOException {
        List<String> remoteOnly = new ArrayList<>();
        int remoteSnapshots = 0;
        for (String name : target.keySet()) {
            if (name.startsWith("snapshots/")) {
                remoteSnapshots++;
            }
            if (rank(name) > 0 && !source.containsKey(name)) {
                remoteOnly.add(name);
            }
        }
        if (remoteOnly.isEmpty()) {
            return new Deletions(List.of(), null);
        }
        List<String> problems = new ArrayList<>();
        List<Snapshot> snapshots = local.snapshots(problems);
        if (snapshots.isEmpty() || !problems.isEmpty()) {
            return new Deletions(List.of(), remoteSnapshots == 0 ? null : snapshots.isEmpty()
                ? "this server's repository has no backups and the remote has " + remoteSnapshots
                    + ", so nothing was removed from the remote"
                : "a local backup can't be read (" + problems.get(0) + "), so nothing was removed from the remote");
        }
        // An index listing a pack that isn't here: the local repository is damaged.
        Set<Hash> indexed;
        try {
            indexed = new HashSet<>(local.index().packs());
        } catch (IOException e) {
            return new Deletions(List.of(), "the local index can't be read (" + e.getMessage()
                + "), so nothing was removed from the remote. /backup verify full, then /backup repair");
        }
        for (Hash pack : indexed) {
            if (!source.containsKey(Pack.fileName(pack))) {
                return new Deletions(List.of(), "the local repository is missing pack " + pack.shortHex()
                    + ", which its index lists, so nothing was removed from the remote. /backup verify full, then"
                    + " /backup repair (or restore from the remote)");
            }
        }
        Instant newest = snapshots.get(snapshots.size() - 1).time();
        Map<Hash, Long> forgotten = local.forgotten();
        Set<String> localSnapshots = new HashSet<>();
        for (Snapshot snapshot : snapshots) {
            localSnapshots.add(snapshot.id().hex());
        }
        List<String> names = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        int unexplained = 0;
        for (String name : remoteOnly) {
            if (!name.startsWith("snapshots/")) {
                continue;
            }
            String hex = name.substring("snapshots/".length());
            Long time = Hash.isHex(hex) ? forgotten.get(Hash.parse(hex)) : null;
            if (time != null && time < newest.toEpochMilli()) {
                names.add(name);
            } else {
                kept.add(hex);
                if (time == null) {
                    unexplained++;
                }
            }
        }
        for (String name : remoteOnly) {
            if (name.startsWith("keep/") && !kept.contains(name.substring("keep/".length()))) {
                names.add(name);
            }
        }
        if (!kept.isEmpty()) {
            // Those snapshots need their packs: leave every pack and index file alone.
            return new Deletions(names, unexplained == 0 ? null : "the remote has " + unexplained + " backup"
                + (unexplained == 1 ? "" : "s") + " this server's repository doesn't, and didn't remove on purpose"
                + " (a damaged, rolled-back or restored disk?), so they and their data were left on the remote");
        }
        // A pack goes only if garbage collection here deleted it on purpose. One that's merely
        // not here (lost with a bad disk, then dropped from the index by the next backup) may
        // be the only good copy, which older backups need.
        Set<Hash> collected = local.collected();
        Set<Hash> lost = local.lost();
        boolean keptPacks = false;
        int lostKept = 0;
        for (String name : target.keySet()) {
            if (!name.startsWith("data/") || source.containsKey(name)) {
                continue;
            }
            Hash pack = Pack.idOf(name);
            if (pack != null && collected.contains(pack) && !indexed.contains(pack) && !lost.contains(pack)) {
                names.add(name);
            } else {
                keptPacks = true;
                lostKept += pack != null && lost.contains(pack) ? 1 : 0;
            }
        }
        // Index files go only once every pack left on the remote is listed by one of this
        // repository's (all sent above): otherwise the remote's own index files are what
        // still list the packs kept there.
        if (!keptPacks) {
            for (String name : remoteOnly) {
                if (name.startsWith("index/")) {
                    names.add(name);
                }
            }
        }
        return new Deletions(names, lostKept == 0 ? null : "the remote still has " + lostKept + " pack"
            + (lostKept == 1 ? "" : "s") + " this server's repository lost, so " + (lostKept == 1 ? "it was" : "they were")
            + " kept there, with the remote's index files. Backups that need " + (lostKept == 1 ? "it" : "them")
            + " can be restored from the remote (java -jar xBackup.jar restore <id> --remote <name>).");
    }

    /**
     * Finds (and with {@code apply}, removes) what the remote keeps that this repository
     * doesn't: see {@link Maintenance#tidyRemote}. Copies first, so the remote has everything
     * here before anything there goes.
     */
    public static Maintenance.Tidy tidy(Repository local, Backend to, boolean apply, BooleanSupplier cancelled,
                                        Consumer<String> progress) throws IOException {
        checkSameRepository(local.backend(), to);
        if (apply) {
            push(local, to, cancelled, progress);
        }
        progress.accept("looking for what only the remote has");
        return new Maintenance(local, cancelled).tidyRemote(to, apply);
    }

    /**
     * Refuses to copy onto a remote that holds a different repository. Copying deletes what
     * the source removed, so after the local repository was lost and started again, copying
     * would otherwise wipe the remote's backups: the very copy that's needed.
     */
    static void checkSameRepository(Backend from, Backend to) throws IOException {
        String local = repositoryId(from);
        String remote;
        try {
            remote = repositoryId(to);
        } catch (java.nio.file.NoSuchFileException e) {
            // A new, empty remote: fine.
            for (String prefix : List.of("snapshots/", "data/", "index/", "keep/")) {
                if (!to.list(prefix).isEmpty()) {
                    throw new IOException(to.describe() + " holds backup data but no repository config, so it isn't"
                        + " overwritten. Point the remote at an empty folder, or restore from it first.");
                }
            }
            return;
        }
        if (!remote.equals(local)) {
            throw new IOException(to.describe() + " holds a different repository (id " + remote + ", this one is " + local
                + "), so it isn't touched: copying would delete its backups. If this server's repository was lost, restore"
                + " from the remote (java -jar xBackup.jar restore latest --remote <name>), or point the remote at an"
                + " empty folder.");
        }
    }

    private static String repositoryId(Backend backend) throws IOException {
        byte[] config;
        try (InputStream in = backend.get("config")) {
            config = in.readNBytes(64 * 1024);
        }
        for (String line : new String(config, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("id=")) {
                return line.substring(3).strip();
            }
        }
        throw new IOException(backend.describe() + " has a repository config without an id.");
    }

    // Where a file goes in the order, or -1 if it isn't a repository file.
    private static int rank(String name) {
        for (int i = 0; i < ORDER.size(); i++) {
            String prefix = ORDER.get(i);
            if (prefix.endsWith("/") ? name.startsWith(prefix) && name.indexOf(".part-") < 0 : name.equals(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static String percent(long done, long total) {
        return total == 0 ? "100%" : (done * 100 / total) + "%";
    }
}
