package co.xenastudios.xbackup.store

import java.nio.file.NoSuchFileException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Random

/** [Calendar] in a time zone: the server's, unless a test gives another. */
public class ZoneCalendar(private val zone: ZoneId = ZoneId.systemDefault()) : Calendar {
    private fun at(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

    override fun hour(epochMillis: Long): Long = at(epochMillis).truncatedTo(ChronoUnit.HOURS).toEpochSecond()

    override fun day(epochMillis: Long): Long = at(epochMillis).truncatedTo(ChronoUnit.DAYS).toEpochSecond()

    override fun week(epochMillis: Long): Long {
        val time = at(epochMillis)
        return time.get(IsoFields.WEEK_BASED_YEAR) * 100L + time.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    }

    override fun month(epochMillis: Long): Long {
        val time = at(epochMillis)
        return time.year * 100L + time.monthValue
    }
}

/**
 * Keeping a repository tidy and honest: forgetting old snapshots, collecting the space they used,
 * checking packs against their hashes, and rebuilding the index from the packs. Everything here
 * needs the repository's exclusive lock, except [check], which needs at least the shared one.
 */
public class Maintenance(private val repository: Repository, private val cancelled: () -> Boolean) {

    private fun checkCancelled() {
        if (cancelled()) throw IOException("stopped", null)
    }

    // ---- marking ----

    /**
     * Every blob the snapshots use. Each blob's value is the position, newest first, of the newest
     * snapshot that uses it, so "what would dropping everything older than N free" is a sum.
     */
    internal fun mark(newestFirst: List<Snapshot>): PrefixMap {
        val used = PrefixMap()
        val seen = HashSet<Hash>()
        for (position in newestFirst.indices) {
            checkCancelled()
            markTree(newestFirst[position].tree, position, used, seen)
        }
        return used
    }

    // Trees and layouts already walked were walked for a newer snapshot, so everything under them
    // already has a newer (smaller) position.
    private fun markTree(treeId: Hash, position: Int, used: PrefixMap, seen: MutableSet<Hash>) {
        if (!seen.add(treeId)) return
        used.putMin(treeId, position)
        for (node in repository.tree(treeId).nodes) {
            when (node.kind) {
                Tree.Kind.DIR -> markTree(node.ref!!, position, used, seen)
                Tree.Kind.REGION -> if (seen.add(node.ref!!)) {
                    used.putMin(node.ref, position)
                    val region = repository.region(node.ref)
                    for (i in 0 until RegionLayout.CHUNKS) region.blob(i)?.let { used.putMin(it, position) }
                }
                Tree.Kind.FILE, Tree.Kind.SQLITE -> for (blob in node.blobs) used.putMin(blob, position)
                Tree.Kind.LINK -> Unit
            }
        }
    }

    // ---- forgetting ----

    /** What [forget] removed, and how many it kept. */
    public class Forgotten(public val removed: List<Snapshot>, public val kept: Int)

    /**
     * Applies [policy], then, if the repository is bigger than [maxBytes] (0 for no limit),
     * forgets the oldest snapshots until what's left would fit. Never forgets the newest snapshot
     * or a pinned one. Only removes snapshot files: run [collect] to free their space.
     */
    public fun forget(policy: Retention, maxBytes: Long, calendar: Calendar): Forgotten {
        val problems = ArrayList<String>()
        val snapshots = repository.snapshots(problems)
        val pinned = repository.pins()
        val keep = policy.keep(snapshots, pinned, calendar)
        val removed = ArrayList<Snapshot>()
        val kept = ArrayList<Snapshot>()
        for (snapshot in snapshots) (if (snapshot.key in keep) kept else removed) += snapshot
        if (maxBytes > 0 && kept.size > 1) {
            val over = overCap(kept, pinned, maxBytes)
            removed += over
            kept.removeAll(over.toSet())
        }
        for (snapshot in removed) repository.deleteSnapshot(snapshot)
        return Forgotten(removed, kept.size)
    }

    // The oldest unpinned snapshots to drop so the repository fits in maxBytes.
    private fun overCap(kept: List<Snapshot>, pinned: Set<Hash>, maxBytes: Long): List<Snapshot> {
        val total = repository.backend.list("data/").sumOf { it.size }
        if (total <= maxBytes) return emptyList()
        val newestFirst = kept.sortedByDescending { it.time }
        val used = mark(newestFirst)
        // onlyFrom[p]: bytes whose newest user is at position p or older.
        val onlyFrom = LongArray(newestFirst.size + 1)
        repository.index().forEach { location ->
            val newest = used[location.entry.id]
            if (newest >= 0) onlyFrom[newest] += location.entry.storedLength.toLong()
        }
        for (p in newestFirst.size - 1 downTo 0) onlyFrom[p] += onlyFrom[p + 1]
        // Keeping positions [0, cut) leaves what they use; the rest is freed once collected.
        for (cut in newestFirst.size downTo 1) {
            if (onlyFrom[0] - onlyFrom[cut] <= maxBytes || cut == 1) {
                return (cut until newestFirst.size).map { newestFirst[it] }.filter { it.key !in pinned }
            }
        }
        return emptyList()
    }

    // ---- collecting ----

    /** What [collect] did. */
    public class Collected(public val packsDeleted: Int, public val packsRepacked: Int, public val bytesFreed: Long, public val bytesBefore: Long, public val bytesAfter: Long)

    /**
     * Garbage collection: marks every blob a snapshot uses, deletes packs with none, and moves the
     * live blobs out of packs that are mostly unused. Also deletes packs no index lists that hold
     * nothing a snapshot needs (only a crashed backup leaves them), and drops index entries for
     * packs that are missing. Every pack it deletes is noted as collected first.
     *
     * Two steps, so it still frees space on a full disk: first a fresh index without the unused
     * packs, and those packs deleted, which writes no pack; only then are mostly unused packs
     * repacked, which needs room for new packs (if there isn't any, that waits for the next run).
     * Crash-safe: the new index is stored before any pack it no longer lists is deleted, and
     * repacked blobs are in new, indexed packs before their old packs go.
     *
     * @param repackUnused repack a pack once at least this share of its bytes is unused (0 to 1)
     */
    public fun collect(options: Session.Options, repackUnused: Double): Collected {
        val problems = ArrayList<String>()
        val snapshots = repository.snapshots(problems)
        // A snapshot we can't read might use packs we'd otherwise delete.
        if (problems.isNotEmpty()) throw CorruptException("Not collecting garbage while a snapshot can't be read: ${problems[0]}")
        val newestFirst = snapshots.sortedByDescending { it.time }
        // Packs gone missing first: the index keeps only a blob's first place, so a blob in a
        // missing pack would look unused in another pack that also holds it.
        repository.dropMissingPacks()
        val index = repository.index()
        val used = try {
            mark(newestFirst)
        } catch (e: CorruptException) {
            // What a damaged backup uses can't be told from garbage; deleting it here would delete
            // it from remotes too, which may still hold that backup whole.
            throw CorruptException("Not collecting garbage while a backup's structure can't be read (${e.message}). /backup verify full says which; restore it from a remote, or /backup forget it.", e)
        }

        val sizes = HashMap<Hash, Long>()
        var before = 0L
        for (file in repository.backend.list("data/")) {
            val pack = PackFormat.idOf(file.name) ?: continue
            sizes[pack] = file.size
            before += file.size
        }
        // The blobs in use, by the pack the index has them in. Packs missing from the disk are
        // left out: there's nothing in them to keep.
        val live = LinkedHashMap<Hash, MutableList<PackEntry>>()
        val liveBytes = HashMap<Hash, Long>()
        var liveCount = 0
        index.forEach { location ->
            if (location.entry.id in used && location.pack in sizes) {
                live.getOrPut(location.pack) { ArrayList() } += location.entry
                liveBytes[location.pack] = (liveBytes[location.pack] ?: 0L) + location.entry.storedLength
                liveCount++
            }
        }

        // A pack no index lists is normally what a crashed backup left. But "not in the index"
        // never means "unused": one holding blobs a snapshot needs that the index has nowhere
        // (a lost index file) is taken back in, and one that can't be read is left alone.
        var adopted = false
        val unreadable = HashSet<Hash>()
        val indexedPacks = HashSet(index.packs())
        val lost = repository.lost()
        for (file in repository.backend.list("data/")) {
            val pack = PackFormat.idOf(file.name)
            if (pack == null || pack in indexedPacks) continue
            checkCancelled()
            if (pack in lost && !whole(file.name, pack)) {
                // Dropped as damaged: taken back only once it's whole again (put back from a remote).
                unreadable += pack
                continue
            }
            val entries = try {
                readFooter(repository.backend, file.name, file.size)
            } catch (e: CorruptException) {
                unreadable += pack
                continue
            }
            val needed = entries.filter { it.id in used && !index.has(it.id) }
            if (needed.isNotEmpty()) {
                live.getOrPut(pack) { ArrayList() } += needed
                for (entry in needed) liveBytes[pack] = (liveBytes[pack] ?: 0L) + entry.storedLength
                adopted = true
            }
        }

        // 1. Drop the unused packs: a fresh index listing only blobs in use, then delete every
        //    pack it doesn't list. Each is noted as collected before it goes.
        if (adopted || live.size != index.packs().size || liveCount != index.size) repository.rewriteIndex(live)
        val unusedPacks = sizes.keys.filter { it !in live && it !in unreadable }
        repository.noteCollected(unusedPacks)
        var deleted = 0
        for (pack in unusedPacks) {
            checkCancelled()
            repository.backend.delete(PackFormat.fileName(pack))
            deleted++
        }

        // 2. Repack the packs that are mostly unused, and many small ones.
        val keep = LinkedHashMap<Hash, List<PackEntry>>()
        val repack = ArrayList<Hash>()
        val smallPack = options.packSize / 8
        var smallCount = 0
        for ((pack, entries) in live) {
            val size = sizes.getValue(pack)
            val liveSize = liveBytes[pack] ?: 0L
            val unused = 1.0 - liveSize.toDouble() / maxOf(1, size)
            if (unused >= repackUnused) {
                repack += pack
            } else {
                if (size < smallPack) smallCount++
                keep[pack] = entries
            }
        }
        // Many small packs (from backups that stored little) are merged too.
        if (smallCount >= 16) {
            for (pack in keep.keys.toList()) {
                if (sizes.getValue(pack) < smallPack) {
                    keep.remove(pack)
                    repack += pack
                }
            }
        }

        var repacked = 0
        if (repack.isNotEmpty()) {
            val fresh: Map<Hash, List<PackEntry>>? = try {
                repository.session(options).use { session ->
                    for (pack in repack) {
                        checkCancelled()
                        val name = PackFormat.fileName(pack)
                        for (entry in live.getValue(pack)) {
                            val stored = repository.backend.read(name, entry.offset, entry.storedLength)
                            Repository.unpack(entry, stored)
                            session.copy(entry, stored)
                        }
                    }
                    session.flush()
                    session.writtenEntries()
                }
            } catch (e: NoSpaceException) {
                // Not enough room for new packs: step 1 freed what it could; repacking waits.
                null
            }
            if (fresh != null) {
                val next = LinkedHashMap(keep)
                next.putAll(fresh)
                repository.rewriteIndex(next)
                val emptied = repack.filter { it !in next }
                repository.noteCollected(emptied)
                for (pack in emptied) {
                    repository.backend.delete(PackFormat.fileName(pack))
                    deleted++
                }
                repacked = repack.size
            }
        }
        (repository.backend as? LocalBackend)?.clearTemp()
        val after = repository.backend.list("data/").filter { PackFormat.idOf(it.name) != null }.sumOf { it.size }
        return Collected(deleted, repacked, maxOf(0, before - after), before, after)
    }

    // ---- remotes ----

    /**
     * What tidying a remote finds, or did.
     *
     * @param extra the remote's backups this repository doesn't have, oldest first
     * @param packs remote packs no backup here needs
     * @param bytes their size
     * @param keptPacks remote packs kept because a backup here needs data only they hold
     */
    public class Tidy(public val extra: List<Snapshot>, public val packs: Int, public val bytes: Long, public val keptPacks: Int)

    /**
     * The way out when a copy keeps everything because the remote has backups this repository
     * doesn't (a rolled-back disk, a list of removed backups that ran out): finds them, and with
     * [apply] removes them from the remote, then every pack there no backup here needs. Packs
     * holding data a backup here needs but this repository lost are kept. Index files only the
     * remote has go only if no such pack is kept. Copy to the remote first.
     */
    public fun tidyRemote(remote: Backend, apply: Boolean): Tidy {
        val problems = ArrayList<String>()
        val snapshots = repository.snapshots(problems)
        if (problems.isNotEmpty() || snapshots.isEmpty()) {
            throw CorruptException(
                if (snapshots.isEmpty()) "This repository has no backups, so nothing on the remote is removed."
                else "A backup here can't be read (${problems[0]}), so nothing on the remote is removed.",
            )
        }
        val local = repository.backend.list("").map { it.name }.toHashSet()
        val extra = ArrayList<Snapshot>()
        for (file in remote.list("snapshots/")) {
            val hex = file.name.substring("snapshots/".length)
            if (file.name in local || !Hash.isHex(hex)) continue
            val bytes = remote.get(file.name).use { readUpTo(it, 16 shl 20) }
            val id = Hash.parse(hex)
            if (Hashes.sha256(bytes) != id) throw CorruptException("the remote's ${file.name} is damaged, so nothing on it is removed")
            extra += Snapshot.decode(id, bytes)
        }
        extra.sortBy { it.time }
        val used = try {
            mark(snapshots.sortedByDescending { it.time })
        } catch (e: CorruptException) {
            throw CorruptException("A backup's structure can't be read here (${e.message}), so nothing on the remote is removed. Restore that backup from the remote first, or /backup forget it.", e)
        }
        val index = repository.index()
        val packs = ArrayList<String>()
        var bytes = 0L
        var kept = 0
        for (file in remote.list("data/")) {
            checkCancelled()
            if (file.name in local || PackFormat.idOf(file.name) == null) continue
            val needed = try {
                readFooter(remote, file.name, file.size).any { it.id in used && !index.has(it.id) }
            } catch (e: CorruptException) {
                true
            }
            if (needed) {
                kept++
            } else {
                packs += file.name
                bytes += file.size
            }
        }
        if (apply) {
            for (snapshot in extra) {
                checkCancelled()
                remote.delete("keep/" + snapshot.key.hex())
                remote.delete(Snapshot.fileName(snapshot.key))
            }
            for (name in packs) {
                checkCancelled()
                remote.delete(name)
            }
            if (kept == 0) for (file in remote.list("index/")) if (file.name !in local) remote.delete(file.name)
        }
        return Tidy(extra, packs.size, bytes, kept)
    }

    // ---- checking ----

    /** What [check] found. */
    public class Report(
        public val snapshots: Int,
        public val packsChecked: Int,
        public val packsTotal: Int,
        public val bytesChecked: Long,
        public val problems: List<String>,
        public val damaged: Set<Hash>,
    ) {
        public val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * Checks the repository: every snapshot's trees can be read and every blob it uses is in the
     * index; the index's packs exist; and a share of the packs (all with 1.0) are read whole,
     * hashed against their names, and each blob in them inflated and hashed against its id.
     *
     * @param only if not `null`, check just these packs instead of a sample (after a backup)
     */
    public fun check(share: Double, only: Set<Hash>?, random: Random): Report {
        val problems = ArrayList<String>()
        val snapshots = repository.snapshots(problems)
        val index = repository.index()
        val present = HashMap<Hash, Long>()
        for (file in repository.backend.list("data/")) PackFormat.idOf(file.name)?.let { present[it] = file.size }
        val missingPacks = index.packs().filter { it !in present }.toHashSet()
        if (only == null) {
            // Structure: every snapshot readable, every blob it uses indexed. Skipped right after a
            // backup, which built the structure it would check.
            val seen = HashSet<Hash>()
            for (snapshot in snapshots) {
                checkCancelled()
                val found = ArrayList<String>()
                checkTree(snapshot.tree, index, missingPacks, seen, found)
                for (problem in found.take(5)) problems += "Snapshot ${snapshot.key.shortHex()}: $problem"
                if (found.size > 5) problems += "Snapshot ${snapshot.key.shortHex()}: and ${found.size - 5} more problems."
            }
        }
        // Packs found damaged or missing: the next backup drops them from the index, so it stores
        // their data again from the live files instead of pointing at what can't be read.
        val damaged = HashSet(missingPacks)
        for (pack in missingPacks) problems += "Pack ${pack.shortHex()} is listed in the index but missing."

        val candidates: List<Hash> = if (only != null) only.toList() else {
            val all = present.keys.toMutableList()
            java.util.Collections.shuffle(all, random)
            val count = if (share >= 1) all.size else Math.ceil(all.size * maxOf(0.0, share)).toInt()
            all.subList(0, minOf(count, all.size)).toList()
        }
        val indexed = index.byPack()
        var bytes = 0L
        var checked = 0
        for (pack in candidates) {
            checkCancelled()
            val name = PackFormat.fileName(pack)
            val data = try {
                repository.backend.readAll(name)
            } catch (e: NoSuchFileException) {
                problems += "Pack ${pack.shortHex()} is missing."
                damaged += pack
                continue
            }
            checked++
            bytes += data.size
            if (Hashes.sha256(data) != pack) {
                problems += "Pack ${pack.shortHex()} is damaged: its content doesn't match its name."
                damaged += pack
                continue
            }
            val footer = try {
                PackFormat.parseFooter(data, name)
            } catch (e: CorruptException) {
                problems += e.message.orEmpty()
                damaged += pack
                continue
            }
            val byId = HashMap<Hash, PackEntry>()
            for (entry in footer) {
                byId[entry.id] = entry
                try {
                    Repository.unpack(entry, data.copyOfRange(entry.offset.toInt(), entry.offset.toInt() + entry.storedLength))
                } catch (e: CorruptException) {
                    problems += "Pack ${pack.shortHex()}: ${e.message}"
                    damaged += pack
                }
            }
            for (entry in indexed[pack].orEmpty()) {
                if (entry != byId[entry.id]) {
                    problems += "Pack ${pack.shortHex()} doesn't hold blob ${entry.id.shortHex()} where the index says."
                    damaged += pack
                }
            }
        }
        return Report(snapshots.size, checked, present.size, bytes, problems, damaged.toSet())
    }

    // Whether a pack's content still matches its name.
    private fun whole(name: String, pack: Hash): Boolean = try {
        Hashes.sha256(repository.backend.readAll(name)) == pack
    } catch (e: NoSuchFileException) {
        false
    }

    private fun checkTree(treeId: Hash, index: Index, missingPacks: Set<Hash>, seen: MutableSet<Hash>, problems: MutableList<String>) {
        if (!seen.add(treeId)) return
        val tree = try {
            repository.tree(treeId)
        } catch (e: CorruptException) {
            problems += e.message.orEmpty()
            return
        }
        for (node in tree.nodes) {
            when (node.kind) {
                Tree.Kind.DIR -> checkTree(node.ref!!, index, missingPacks, seen, problems)
                Tree.Kind.REGION -> {
                    if (!seen.add(node.ref!!)) continue
                    try {
                        val region = repository.region(node.ref)
                        for (i in 0 until RegionLayout.CHUNKS) region.blob(i)?.let { checkBlob(it, "${node.name} chunk $i", index, missingPacks, problems) }
                    } catch (e: CorruptException) {
                        problems += "${node.name}: ${e.message}"
                    }
                }
                Tree.Kind.FILE, Tree.Kind.SQLITE -> {
                    var total = 0L
                    // The size is checked whenever this file's own pieces are all found.
                    val before = problems.size
                    for (blob in node.blobs) checkBlob(blob, node.name, index, missingPacks, problems)?.let { total += it.entry.rawLength }
                    if (total != node.contentSize && problems.size == before) problems += "${node.name} adds up to $total bytes, not ${node.contentSize}"
                }
                Tree.Kind.LINK -> Unit
            }
        }
    }

    private fun checkBlob(blob: Hash, what: String, index: Index, missingPacks: Set<Hash>, problems: MutableList<String>): Index.Location? {
        val location = index.find(blob)
        if (location == null) {
            problems += "$what uses blob ${blob.shortHex()}, which isn't in any pack"
        } else if (location.pack in missingPacks) {
            problems += "$what uses blob ${blob.shortHex()} in missing pack ${location.pack.shortHex()}"
        }
        return location
    }

    // ---- repairing ----

    /** What [rebuildIndex] found. */
    public class Rebuilt(public val packs: Int, public val blobs: Int, public val damaged: List<String>)

    /**
     * Rebuilds the index from the packs' own footers and replaces every index file with one. A
     * pack whose footer can't be read is left alone and listed as damaged.
     */
    public fun rebuildIndex(): Rebuilt {
        val packs = LinkedHashMap<Hash, List<PackEntry>>()
        val damaged = ArrayList<String>()
        var blobs = 0
        for (file in repository.backend.list("data/")) {
            checkCancelled()
            val pack = PackFormat.idOf(file.name) ?: continue
            try {
                val entries = readFooter(repository.backend, file.name, file.size)
                packs[pack] = entries
                blobs += entries.size
            } catch (e: CorruptException) {
                damaged += e.message.orEmpty()
            }
        }
        // Packs the old index listed that aren't here: noted as lost, so remotes keep theirs.
        val missing = try {
            repository.index().packs().filter { it !in packs }
        } catch (e: CorruptException) {
            // The old index is what's being repaired; remotes keep every pack anyway unless it's
            // noted as collected.
            emptyList()
        }
        if (missing.isNotEmpty()) repository.noteLost(missing)
        repository.rewriteIndex(packs)
        return Rebuilt(packs.size, blobs, damaged)
    }
}
