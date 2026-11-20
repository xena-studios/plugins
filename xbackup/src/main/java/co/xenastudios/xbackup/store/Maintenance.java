package co.xenastudios.xbackup.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Keeping a repository tidy and honest: forgetting old snapshots, collecting the space they
 * used, checking packs against their hashes, and rebuilding the index from the packs.
 * Everything here needs the repository's exclusive lock, except {@link #check}, which needs
 * at least the shared one.
 */
public final class Maintenance {

    private final Repository repository;
    private final BooleanSupplier cancelled;

    public Maintenance(Repository repository, BooleanSupplier cancelled) {
        this.repository = repository;
        this.cancelled = cancelled;
    }

    private void checkCancelled() throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new IOException("stopped");
        }
    }

    // ---------------------------------------------------------------- marking

    /**
     * Every blob the snapshots use. Each blob's value is the position, newest first, of the
     * newest snapshot that uses it, so "what would dropping everything older than N free"
     * is a sum.
     */
    PrefixMap mark(List<Snapshot> newestFirst) throws IOException {
        PrefixMap used = new PrefixMap();
        Set<Hash> seen = new HashSet<>();
        for (int position = 0; position < newestFirst.size(); position++) {
            checkCancelled();
            markTree(newestFirst.get(position).tree(), position, used, seen);
        }
        return used;
    }

    // Trees and layouts already walked were walked for a newer snapshot, so everything
    // under them already has a newer (smaller) position.
    private void markTree(Hash treeId, int position, PrefixMap used, Set<Hash> seen) throws IOException {
        if (!seen.add(treeId)) {
            return;
        }
        used.putMin(treeId, position);
        for (Tree.Node node : repository.tree(treeId).nodes()) {
            switch (node.kind()) {
                case DIR -> markTree(node.ref(), position, used, seen);
                case REGION -> {
                    if (seen.add(node.ref())) {
                        used.putMin(node.ref(), position);
                        RegionLayout region = repository.region(node.ref());
                        for (int i = 0; i < RegionLayout.CHUNKS; i++) {
                            if (region.blob(i) != null) {
                                used.putMin(region.blob(i), position);
                            }
                        }
                    }
                }
                case FILE, SQLITE -> {
                    for (Hash blob : node.blobs()) {
                        used.putMin(blob, position);
                    }
                }
                case LINK -> {
                    // Nothing stored.
                }
            }
        }
    }

    // ---------------------------------------------------------------- forgetting

    /** What {@link #forget} removed, and why. */
    public record Forgotten(List<Snapshot> removed, int kept) {}

    /**
     * Applies the retention policy, then, if the repository is bigger than {@code maxBytes}
     * (0 for no limit), forgets the oldest snapshots until what's left would fit. Never
     * forgets the newest snapshot or a pinned one. Only removes snapshot files: run
     * {@link #collect} to free their space.
     */
    public Forgotten forget(Retention policy, long maxBytes, ZoneId zone) throws IOException {
        List<String> problems = new ArrayList<>();
        List<Snapshot> snapshots = repository.snapshots(problems);
        Set<Hash> pinned = repository.pins();
        Set<Hash> keep = policy.keep(snapshots, pinned, zone);
        List<Snapshot> removed = new ArrayList<>();
        List<Snapshot> kept = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            (keep.contains(snapshot.id()) ? kept : removed).add(snapshot);
        }
        if (maxBytes > 0 && kept.size() > 1) {
            removed.addAll(overCap(kept, pinned, maxBytes));
            kept.removeAll(removed);
        }
        for (Snapshot snapshot : removed) {
            repository.deleteSnapshot(snapshot);
        }
        return new Forgotten(removed, kept.size());
    }

    // The oldest unpinned snapshots to drop so the repository fits in maxBytes.
    private List<Snapshot> overCap(List<Snapshot> kept, Set<Hash> pinned, long maxBytes) throws IOException {
        long total = 0;
        for (Backend.FileInfo file : repository.backend().list("data/")) {
            total += file.size();
        }
        if (total <= maxBytes) {
            return List.of();
        }
        List<Snapshot> newestFirst = new ArrayList<>(kept);
        newestFirst.sort(Comparator.comparing(Snapshot::time).reversed());
        PrefixMap used = mark(newestFirst);
        // onlyFrom[p]: bytes whose newest user is at position p or older.
        long[] onlyFrom = new long[newestFirst.size() + 1];
        repository.index().forEach(location -> {
            int newest = used.get(location.entry().id());
            if (newest >= 0) {
                onlyFrom[newest] += location.entry().storedLength();
            }
        });
        for (int p = newestFirst.size() - 1; p >= 0; p--) {
            onlyFrom[p] += onlyFrom[p + 1];
        }
        // Keeping positions [0, cut) leaves what they use; the rest is freed once collected.
        for (int cut = newestFirst.size(); cut >= 1; cut--) {
            if (onlyFrom[0] - onlyFrom[cut] <= maxBytes || cut == 1) {
                List<Snapshot> drop = new ArrayList<>();
                for (int p = cut; p < newestFirst.size(); p++) {
                    if (!pinned.contains(newestFirst.get(p).id())) {
                        drop.add(newestFirst.get(p));
                    }
                }
                return drop;
            }
        }
        return List.of();
    }

    // ---------------------------------------------------------------- collecting

    /** What {@link #collect} did. */
    public record Collected(int packsDeleted, int packsRepacked, long bytesFreed, long bytesBefore, long bytesAfter) {}

    /**
     * Garbage collection: marks every blob a snapshot uses, deletes packs with none, and
     * moves the live blobs out of packs that are mostly unused. Also deletes packs no index
     * lists that hold nothing a snapshot needs, which only a crashed backup leaves, and drops
     * index entries for packs that are missing, so the next backup stores those blobs again
     * instead of pointing at nothing. Every pack it deletes is noted as collected first.
     *
     * <p>Two steps, so it still frees space on a full disk. First a fresh index without the
     * unused packs is written and those packs deleted: that writes no pack. Only then are
     * mostly unused packs repacked, which needs room for new packs; if there isn't any, that
     * step waits for the next run. Crash-safe: the new index is stored before any pack it no
     * longer lists is deleted, and repacked blobs are in new, indexed packs before their old
     * packs go.
     *
     * @param repackUnused repack a pack once at least this share of its bytes is unused (0 to 1)
     */
    public Collected collect(Session.Options options, double repackUnused) throws IOException {
        List<String> problems = new ArrayList<>();
        List<Snapshot> snapshots = repository.snapshots(problems);
        if (!problems.isEmpty()) {
            // A snapshot we can't read might use packs we'd otherwise delete.
            throw new CorruptException("Not collecting garbage while a snapshot can't be read: " + problems.get(0));
        }
        List<Snapshot> newestFirst = new ArrayList<>(snapshots);
        newestFirst.sort(Comparator.comparing(Snapshot::time).reversed());
        // Packs gone missing first: the index keeps only a blob's first place, so a blob in a
        // missing pack would look unused in another pack that also holds it.
        repository.dropMissingPacks();
        Index index = repository.index();
        PrefixMap used;
        try {
            used = mark(newestFirst);
        } catch (CorruptException e) {
            // What a damaged backup uses can't be told from garbage; deleting it here would
            // delete it from remotes too, which may still hold that backup whole.
            throw new CorruptException("Not collecting garbage while a backup's structure can't be read ("
                + e.getMessage() + "). /backup verify full says which; restore it from a remote, or /backup forget it.", e);
        }

        Map<Hash, Long> sizes = new HashMap<>();
        long before = 0;
        for (Backend.FileInfo file : repository.backend().list("data/")) {
            Hash pack = Pack.idOf(file.name());
            if (pack != null) {
                sizes.put(pack, file.size());
                before += file.size();
            }
        }
        // The blobs in use, by the pack the index has them in. Packs missing from the disk are
        // left out: there's nothing in them to keep.
        Map<Hash, List<PackEntry>> live = new LinkedHashMap<>();
        Map<Hash, Long> liveBytes = new HashMap<>();
        int[] liveCount = {0};
        index.forEach(location -> {
            if (used.contains(location.entry().id()) && sizes.containsKey(location.pack())) {
                live.computeIfAbsent(location.pack(), key -> new ArrayList<>()).add(location.entry());
                liveBytes.merge(location.pack(), (long) location.entry().storedLength(), Long::sum);
                liveCount[0]++;
            }
        });

        // A pack no index lists is normally what a crashed backup left. But "not in the index"
        // never means "unused": one holding blobs a snapshot needs that the index has nowhere
        // (a lost index file) is taken back in, and one that can't be read is left alone.
        boolean adopted = false;
        Set<Hash> unreadable = new HashSet<>();
        Set<Hash> indexedPacks = new HashSet<>(index.packs());
        Set<Hash> lost = repository.lost();
        for (Backend.FileInfo file : repository.backend().list("data/")) {
            Hash pack = Pack.idOf(file.name());
            if (pack == null || indexedPacks.contains(pack)) {
                continue;
            }
            checkCancelled();
            if (lost.contains(pack) && !whole(file.name(), pack)) {
                // Dropped as damaged: taken back only once it's whole again (put back from a remote).
                unreadable.add(pack);
                continue;
            }
            List<PackEntry> entries;
            try {
                entries = Pack.readFooter(repository.backend(), file.name(), file.size());
            } catch (CorruptException e) {
                unreadable.add(pack);
                continue;
            }
            List<PackEntry> needed = new ArrayList<>();
            for (PackEntry entry : entries) {
                if (used.contains(entry.id()) && !index.has(entry.id())) {
                    needed.add(entry);
                }
            }
            if (!needed.isEmpty()) {
                live.computeIfAbsent(pack, key -> new ArrayList<>()).addAll(needed);
                for (PackEntry entry : needed) {
                    liveBytes.merge(pack, (long) entry.storedLength(), Long::sum);
                }
                adopted = true;
            }
        }

        // 1. Drop the unused packs: a fresh index listing only blobs in use, then delete
        //    every pack it doesn't list. Writes one index file and no pack. Each is noted as
        //    collected before it goes, so copies on remotes go too.
        if (adopted || live.size() != index.packs().size() || liveCount[0] != index.size()) {
            repository.rewriteIndex(live);
        }
        List<Hash> unusedPacks = new ArrayList<>();
        for (Hash pack : sizes.keySet()) {
            if (!live.containsKey(pack) && !unreadable.contains(pack)) {
                unusedPacks.add(pack);
            }
        }
        repository.noteCollected(unusedPacks);
        int deleted = 0;
        for (Hash pack : unusedPacks) {
            checkCancelled();
            repository.backend().delete(Pack.fileName(pack));
            deleted++;
        }

        // 2. Repack the packs that are mostly unused, and many small ones.
        Map<Hash, List<PackEntry>> keep = new LinkedHashMap<>();
        List<Hash> repack = new ArrayList<>();
        long smallPack = options.packSize() / 8;
        int smallCount = 0;
        for (Map.Entry<Hash, List<PackEntry>> pack : live.entrySet()) {
            long size = sizes.get(pack.getKey());
            long liveSize = liveBytes.getOrDefault(pack.getKey(), 0L);
            double unused = 1.0 - (double) liveSize / Math.max(1, size);
            if (unused >= repackUnused) {
                repack.add(pack.getKey());
            } else {
                if (size < smallPack) {
                    smallCount++;
                }
                keep.put(pack.getKey(), pack.getValue());
            }
        }
        // Many small packs (from backups that stored little) are merged too.
        if (smallCount >= 16) {
            for (Hash pack : List.copyOf(keep.keySet())) {
                if (sizes.get(pack) < smallPack) {
                    keep.remove(pack);
                    repack.add(pack);
                }
            }
        }

        int repacked = 0;
        if (!repack.isEmpty()) {
            Map<Hash, List<PackEntry>> fresh;
            try (Session session = repository.session(options)) {
                for (Hash pack : repack) {
                    checkCancelled();
                    String name = Pack.fileName(pack);
                    for (PackEntry entry : live.get(pack)) {
                        byte[] stored = repository.backend().read(name, entry.offset(), entry.storedLength());
                        Repository.unpack(entry, stored);
                        session.copy(entry, stored);
                    }
                }
                session.flush();
                fresh = session.writtenEntries();
            } catch (Session.NoSpaceException e) {
                // Not enough room for new packs: step 1 freed what it could; repacking waits.
                fresh = null;
            }
            if (fresh != null) {
                Map<Hash, List<PackEntry>> next = new LinkedHashMap<>(keep);
                next.putAll(fresh);
                repository.rewriteIndex(next);
                List<Hash> emptied = new ArrayList<>();
                for (Hash pack : repack) {
                    if (!next.containsKey(pack)) {
                        emptied.add(pack);
                    }
                }
                repository.noteCollected(emptied);
                for (Hash pack : emptied) {
                    repository.backend().delete(Pack.fileName(pack));
                    deleted++;
                }
                repacked = repack.size();
            }
        }
        if (repository.backend() instanceof LocalBackend local) {
            local.clearTemp();
        }
        long after = 0;
        for (Backend.FileInfo file : repository.backend().list("data/")) {
            if (Pack.idOf(file.name()) != null) {
                after += file.size();
            }
        }
        return new Collected(deleted, repacked, Math.max(0, before - after), before, after);
    }

    // ---------------------------------------------------------------- remotes

    /**
     * What tidying a remote finds, or did.
     *
     * @param extra the remote's backups this repository doesn't have, oldest first
     * @param packs remote packs no backup here needs
     * @param bytes their size
     * @param keptPacks remote packs kept because a backup here needs data only they hold
     */
    public record Tidy(List<Snapshot> extra, int packs, long bytes, int keptPacks) {}

    /**
     * The way out when a copy keeps everything because the remote has backups this repository
     * doesn't (a rolled-back disk, a list of removed backups that ran out): finds them, and with
     * {@code apply} removes them from the remote, then every pack there no backup here needs.
     * Packs holding data a backup here needs but this repository lost are kept. Index files
     * only the remote has go only if no such pack is kept. Copy to the remote first.
     */
    public Tidy tidyRemote(Backend remote, boolean apply) throws IOException {
        List<String> problems = new ArrayList<>();
        List<Snapshot> snapshots = repository.snapshots(problems);
        if (!problems.isEmpty() || snapshots.isEmpty()) {
            throw new CorruptException(snapshots.isEmpty() ? "This repository has no backups, so nothing on the remote is"
                + " removed." : "A backup here can't be read (" + problems.get(0) + "), so nothing on the remote is removed.");
        }
        Set<String> local = new HashSet<>();
        for (Backend.FileInfo file : repository.backend().list("")) {
            local.add(file.name());
        }
        // The remote's other backups.
        List<Snapshot> extra = new ArrayList<>();
        for (Backend.FileInfo file : remote.list("snapshots/")) {
            String hex = file.name().substring("snapshots/".length());
            if (local.contains(file.name()) || !Hash.isHex(hex)) {
                continue;
            }
            byte[] bytes;
            try (InputStream in = remote.get(file.name())) {
                bytes = in.readNBytes(16 << 20);
            }
            Hash id = Hash.parse(hex);
            if (!Hash.sha256(bytes).equals(id)) {
                throw new CorruptException("the remote's " + file.name() + " is damaged, so nothing on it is removed");
            }
            extra.add(Snapshot.decode(id, bytes));
        }
        extra.sort(Comparator.comparing(Snapshot::time));
        // What the backups here use: once the others are gone, those are all the remote keeps.
        List<Snapshot> newestFirst = new ArrayList<>(snapshots);
        newestFirst.sort(Comparator.comparing(Snapshot::time).reversed());
        PrefixMap used;
        try {
            used = mark(newestFirst);
        } catch (CorruptException e) {
            throw new CorruptException("A backup's structure can't be read here (" + e.getMessage() + "), so nothing on"
                + " the remote is removed. Restore that backup from the remote first, or /backup forget it.", e);
        }
        Index index = repository.index();
        List<String> packs = new ArrayList<>();
        long bytes = 0;
        int kept = 0;
        for (Backend.FileInfo file : remote.list("data/")) {
            checkCancelled();
            if (local.contains(file.name()) || Pack.idOf(file.name()) == null) {
                continue;
            }
            boolean needed;
            try {
                needed = false;
                for (PackEntry entry : Pack.readFooter(remote, file.name(), file.size())) {
                    if (used.contains(entry.id()) && !index.has(entry.id())) {
                        needed = true;
                        break;
                    }
                }
            } catch (CorruptException e) {
                needed = true;
            }
            if (needed) {
                kept++;
            } else {
                packs.add(file.name());
                bytes += file.size();
            }
        }
        if (apply) {
            for (Snapshot snapshot : extra) {
                checkCancelled();
                remote.delete("keep/" + snapshot.id().hex());
                remote.delete(Snapshot.fileName(snapshot.id()));
            }
            for (String name : packs) {
                checkCancelled();
                remote.delete(name);
            }
            if (kept == 0) {
                for (Backend.FileInfo file : remote.list("index/")) {
                    if (!local.contains(file.name())) {
                        remote.delete(file.name());
                    }
                }
            }
        }
        return new Tidy(extra, packs.size(), bytes, kept);
    }

    // ---------------------------------------------------------------- checking

    /** What {@link #check} found. */
    public record Report(int snapshots, int packsChecked, int packsTotal, long bytesChecked, List<String> problems,
                         Set<Hash> damaged) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * Checks the repository: every snapshot's trees can be read and every blob it uses is in
     * the index; the index's packs exist; and a share of the packs (all with 1.0) are read
     * whole, hashed against their names, and each blob in them inflated and hashed against
     * its id.
     *
     * @param only if not null, check just these packs instead of a sample (after a backup)
     */
    public Report check(double share, Set<Hash> only, Random random) throws IOException {
        List<String> problems = new ArrayList<>();
        List<Snapshot> snapshots = repository.snapshots(problems);
        Index index = repository.index();

        Map<Hash, Long> present = new HashMap<>();
        for (Backend.FileInfo file : repository.backend().list("data/")) {
            Hash pack = Pack.idOf(file.name());
            if (pack != null) {
                present.put(pack, file.size());
            }
        }
        Set<Hash> missingPacks = new HashSet<>();
        for (Hash pack : index.packs()) {
            if (!present.containsKey(pack)) {
                missingPacks.add(pack);
            }
        }
        if (only == null) {
            // Structure: every snapshot readable, every blob it uses indexed. Skipped right
            // after a backup, which built the structure it would check.
            Set<Hash> seen = new HashSet<>();
            for (Snapshot snapshot : snapshots) {
                checkCancelled();
                List<String> found = new ArrayList<>();
                checkTree(snapshot.tree(), index, missingPacks, seen, found);
                for (String problem : found.subList(0, Math.min(found.size(), 5))) {
                    problems.add("Snapshot " + snapshot.id().shortHex() + ": " + problem);
                }
                if (found.size() > 5) {
                    problems.add("Snapshot " + snapshot.id().shortHex() + ": and " + (found.size() - 5) + " more problems.");
                }
            }
        }
        // Packs found damaged or missing: the next backup drops them from the index, so it
        // stores their data again from the live files instead of pointing at what can't be read.
        Set<Hash> damaged = new HashSet<>(missingPacks);
        for (Hash pack : missingPacks) {
            problems.add("Pack " + pack.shortHex() + " is listed in the index but missing.");
        }

        List<Hash> candidates = new ArrayList<>();
        if (only != null) {
            candidates.addAll(only);
        } else {
            candidates.addAll(present.keySet());
            Collections.shuffle(candidates, random);
            int count = share >= 1 ? candidates.size() : (int) Math.ceil(candidates.size() * Math.max(0, share));
            candidates = new ArrayList<>(candidates.subList(0, Math.min(count, candidates.size())));
        }
        Map<Hash, List<PackEntry>> indexed = index.byPack();
        long bytes = 0;
        int checked = 0;
        for (Hash pack : candidates) {
            checkCancelled();
            String name = Pack.fileName(pack);
            byte[] data;
            try (InputStream in = repository.backend().get(name)) {
                data = in.readAllBytes();
            } catch (NoSuchFileException e) {
                problems.add("Pack " + pack.shortHex() + " is missing.");
                damaged.add(pack);
                continue;
            }
            checked++;
            bytes += data.length;
            if (!Hash.sha256(data).equals(pack)) {
                problems.add("Pack " + pack.shortHex() + " is damaged: its content doesn't match its name.");
                damaged.add(pack);
                continue;
            }
            List<PackEntry> footer;
            try {
                footer = Pack.parseFooter(data, name);
            } catch (CorruptException e) {
                problems.add(e.getMessage());
                damaged.add(pack);
                continue;
            }
            Map<Hash, PackEntry> byId = new HashMap<>();
            for (PackEntry entry : footer) {
                byId.put(entry.id(), entry);
                try {
                    byte[] stored = java.util.Arrays.copyOfRange(data, (int) entry.offset(),
                        (int) entry.offset() + entry.storedLength());
                    Repository.unpack(entry, stored);
                } catch (CorruptException e) {
                    problems.add("Pack " + pack.shortHex() + ": " + e.getMessage());
                    damaged.add(pack);
                }
            }
            for (PackEntry entry : indexed.getOrDefault(pack, List.of())) {
                if (!entry.equals(byId.get(entry.id()))) {
                    problems.add("Pack " + pack.shortHex() + " doesn't hold blob " + entry.id().shortHex()
                        + " where the index says.");
                    damaged.add(pack);
                }
            }
        }
        return new Report(snapshots.size(), checked, present.size(), bytes, problems, Set.copyOf(damaged));
    }

    // Whether a pack's content still matches its name.
    private boolean whole(String name, Hash pack) throws IOException {
        try (InputStream in = repository.backend().get(name)) {
            return Hash.sha256(in.readAllBytes()).equals(pack);
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private void checkTree(Hash treeId, Index index, Set<Hash> missingPacks, Set<Hash> seen, List<String> problems)
        throws IOException {
        if (!seen.add(treeId)) {
            return;
        }
        Tree tree;
        try {
            tree = repository.tree(treeId);
        } catch (CorruptException e) {
            problems.add(e.getMessage());
            return;
        }
        for (Tree.Node node : tree.nodes()) {
            switch (node.kind()) {
                case DIR -> checkTree(node.ref(), index, missingPacks, seen, problems);
                case REGION -> {
                    if (!seen.add(node.ref())) {
                        continue;
                    }
                    try {
                        RegionLayout region = repository.region(node.ref());
                        for (int i = 0; i < RegionLayout.CHUNKS; i++) {
                            if (region.blob(i) != null) {
                                checkBlob(region.blob(i), node.name() + " chunk " + i, index, missingPacks, problems);
                            }
                        }
                    } catch (CorruptException e) {
                        problems.add(node.name() + ": " + e.getMessage());
                    }
                }
                case FILE, SQLITE -> {
                    long total = 0;
                    // The size is checked whenever this file's own pieces are all found, whatever
                    // else this snapshot has wrong.
                    int before = problems.size();
                    for (Hash blob : node.blobs()) {
                        Index.Location location = checkBlob(blob, node.name(), index, missingPacks, problems);
                        if (location != null) {
                            total += location.entry().rawLength();
                        }
                    }
                    if (total != node.contentSize() && problems.size() == before) {
                        problems.add(node.name() + " adds up to " + total + " bytes, not " + node.contentSize());
                    }
                }
                case LINK -> {
                    // Nothing stored.
                }
            }
        }
    }

    private static Index.Location checkBlob(Hash blob, String what, Index index, Set<Hash> missingPacks,
                                            List<String> problems) {
        Index.Location location = index.find(blob);
        if (location == null) {
            problems.add(what + " uses blob " + blob.shortHex() + ", which isn't in any pack");
        } else if (missingPacks.contains(location.pack())) {
            problems.add(what + " uses blob " + blob.shortHex() + " in missing pack " + location.pack().shortHex());
        }
        return location;
    }

    // ---------------------------------------------------------------- repairing

    /** What {@link #rebuildIndex} found. */
    public record Rebuilt(int packs, int blobs, List<String> damaged) {}

    /**
     * Rebuilds the index from the packs' own footers and replaces every index file with
     * one. A pack whose footer can't be read is left alone and listed as damaged.
     */
    public Rebuilt rebuildIndex() throws IOException {
        Map<Hash, List<PackEntry>> packs = new LinkedHashMap<>();
        List<String> damaged = new ArrayList<>();
        int blobs = 0;
        for (Backend.FileInfo file : repository.backend().list("data/")) {
            checkCancelled();
            Hash pack = Pack.idOf(file.name());
            if (pack == null) {
                continue;
            }
            try {
                List<PackEntry> entries = Pack.readFooter(repository.backend(), file.name(), file.size());
                packs.put(pack, entries);
                blobs += entries.size();
            } catch (CorruptException e) {
                damaged.add(e.getMessage());
            }
        }
        // Packs the old index listed that aren't here: noted as lost, so remotes keep theirs.
        List<Hash> missing = new ArrayList<>();
        try {
            for (Hash pack : repository.index().packs()) {
                if (!packs.containsKey(pack)) {
                    missing.add(pack);
                }
            }
        } catch (CorruptException e) {
            // The old index is what's being repaired; remotes keep every pack anyway unless
            // it's noted as collected.
        }
        if (!missing.isEmpty()) {
            repository.noteLost(missing);
        }
        repository.rewriteIndex(packs);
        return new Rebuilt(packs.size(), blobs, damaged);
    }
}
