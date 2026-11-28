package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.Index
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.PackFormat
import co.xenastudios.xbackup.store.RegionLayout
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Tree
import co.xenastudios.xbackup.store.readUpTo
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A remote's settings as the backends use them: its section of config.yml plus its secrets from
 * `secrets.yml`, which never sit in config.yml.
 *
 * @param password s3: the secret key; sftp, ftp, webdav: the password (sftp with a key: its passphrase)
 * @param clientSecret drive: the app's client secret
 */
public data class RemoteConfig(
    val type: String,
    val enabled: Boolean,
    val path: String,
    val url: String,
    val region: String,
    val bucket: String,
    val pathStyle: Boolean,
    val username: String,
    val password: String,
    val host: String,
    val port: Int,
    val privateKey: String,
    val hostKey: String,
    val security: String,
    val clientId: String,
    val clientSecret: String,
) {
    /** Everything but the secrets, which never reach a log or an exception's message. */
    override fun toString(): String =
        "RemoteConfig[type=$type, enabled=$enabled, path=$path, url=$url, region=$region, bucket=$bucket, pathStyle=$pathStyle, " +
            "username=$username, password=${if (password.isEmpty()) "" else "(set)"}, host=$host, port=$port, privateKey=$privateKey, " +
            "hostKey=$hostKey, security=$security, clientId=$clientId, clientSecret=${if (clientSecret.isEmpty()) "" else "(set)"}]"
}

/**
 * Keeps a copy of a repository on another backend. Files never change, so a copy is incremental
 * by nature: anything the remote already has (same name, same size) is skipped, so an interrupted
 * copy picks up where it stopped. Packs go first, then index files, then snapshots, so a remote
 * never lists a snapshot whose data isn't there yet; and deletions run the other way round.
 *
 * Deleting is where a copy can do harm: the remote is the copy that's needed when the local
 * repository is damaged, rolled back with its disk, or restored from an old copy. So the remote
 * only loses what this repository removed on purpose:
 * - a snapshot only if the repository's `forgotten` list has it, and it's older than the newest
 *   local snapshot;
 * - packs only if every snapshot left on the remote is also here, and garbage collection here
 *   deleted the pack on purpose (`collected`); never one that went missing here (`lost`);
 * - index files only if every pack left on the remote is listed by a local index file;
 * - nothing at all if the local repository has no snapshots while the remote has some, or if a
 *   local index lists a pack that's missing here (local damage).
 */
public object Mirror {
    private val ORDER = listOf("config", "data/", "index/", "keep/", "snapshots/")

    /** What a copy did; [held] says why some deletions were held back, for the console. */
    public class Result(public val filesSent: Int, public val bytesSent: Long, public val filesDeleted: Int, public val held: String?)

    @JvmStatic
    public fun push(local: Repository, to: Backend, cancelled: () -> Boolean, progress: (String) -> Unit): Result {
        val from = local.backend
        checkSameRepository(from, to)
        // Partial copies a crash left: only this server writes to its remotes, one copy at a time.
        (to as? LocalBackend)?.clearTemp()
        val source = HashMap<String, Long>()
        for (file in from.list("")) if (rank(file.name) >= 0) source[file.name] = file.size
        val target = HashMap<String, Long>()
        for (file in to.list("")) target[file.name] = file.size
        val send = source.filter { it.value != target[it.key] }.keys.sortedWith(compareBy<String> { rank(it) }.thenBy { it })
        val total = send.sumOf { source.getValue(it) }
        var sent = 0
        var bytes = 0L
        for (name in send) {
            if (cancelled()) throw java.io.IOException("stopped")
            progress("$sent of ${send.size} files, ${percent(bytes, total)}")
            val file = from.localFile(name)
            if (file != null) {
                to.put(name, file)
            } else {
                val temp = Files.createTempFile("xbackup", ".part")
                try {
                    from.get(name).use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
                    to.put(name, temp)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
            sent++
            bytes += source.getValue(name)
        }
        val deletions = deletions(local, source, target)
        val remove = deletions.names.sortedByDescending { rank(it) }
        for (name in remove) {
            if (cancelled()) throw java.io.IOException("stopped")
            to.delete(name)
        }
        return Result(sent, bytes, remove.size, deletions.held)
    }

    /** What a copy may delete from the remote, and why it held back the rest. */
    internal class Deletions(val names: List<String>, val held: String?)

    internal fun deletions(local: Repository, source: Map<String, Long>, target: Map<String, Long>): Deletions {
        val remoteOnly = ArrayList<String>()
        var remoteSnapshots = 0
        for (name in target.keys) {
            if (name.startsWith("snapshots/")) remoteSnapshots++
            if (rank(name) > 0 && name !in source) remoteOnly += name
        }
        if (remoteOnly.isEmpty()) return Deletions(emptyList(), null)
        val problems = ArrayList<String>()
        val snapshots = local.snapshots(problems)
        if (snapshots.isEmpty() || problems.isNotEmpty()) {
            return Deletions(
                emptyList(),
                if (remoteSnapshots == 0) null
                else if (snapshots.isEmpty()) "this server's repository has no backups and the remote has $remoteSnapshots, so nothing was removed from the remote"
                else "a local backup can't be read (${problems[0]}), so nothing was removed from the remote",
            )
        }
        // An index listing a pack that isn't here: the local repository is damaged.
        val indexed = try {
            local.index().packs().toHashSet()
        } catch (e: java.io.IOException) {
            return Deletions(emptyList(), "the local index can't be read (${e.message}), so nothing was removed from the remote. /backup verify full, then /backup repair")
        }
        for (pack in indexed) {
            if (PackFormat.fileName(pack) !in source) {
                return Deletions(
                    emptyList(),
                    "the local repository is missing pack ${pack.shortHex()}, which its index lists, so nothing was removed from the remote. /backup verify full, then /backup repair (or restore from the remote)",
                )
            }
        }
        val newest = snapshots.last().time.toEpochMilliseconds()
        val forgotten = local.forgotten()
        val names = ArrayList<String>()
        val kept = HashSet<String>()
        var unexplained = 0
        for (name in remoteOnly) {
            if (!name.startsWith("snapshots/")) continue
            val hex = name.substring("snapshots/".length)
            val time = if (Hash.isHex(hex)) forgotten[Hash.parse(hex)] else null
            if (time != null && time < newest) {
                names += name
            } else {
                kept += hex
                if (time == null) unexplained++
            }
        }
        for (name in remoteOnly) if (name.startsWith("keep/") && name.substring("keep/".length) !in kept) names += name
        if (kept.isNotEmpty()) {
            // Those snapshots need their packs: leave every pack and index file alone.
            return Deletions(
                names,
                if (unexplained == 0) null
                else "the remote has $unexplained backup${if (unexplained == 1) "" else "s"} this server's repository doesn't, and didn't remove on purpose (a damaged, rolled-back or restored disk?), so they and their data were left on the remote",
            )
        }
        // A pack goes only if garbage collection here deleted it on purpose. One that's merely not
        // here (lost with a bad disk, then dropped from the index by the next backup) may be the
        // only good copy, which older backups need.
        val collected = local.collected()
        val lost = local.lost()
        var keptPacks = false
        var lostKept = 0
        for (name in target.keys) {
            if (!name.startsWith("data/") || name in source) continue
            val pack = PackFormat.idOf(name)
            if (pack != null && pack in collected && pack !in indexed && pack !in lost) {
                names += name
            } else {
                keptPacks = true
                if (pack != null && pack in lost) lostKept++
            }
        }
        // Index files go only once every pack left on the remote is listed by one of this
        // repository's: otherwise the remote's own index files are what still list the packs kept there.
        if (!keptPacks) for (name in remoteOnly) if (name.startsWith("index/")) names += name
        val one = lostKept == 1
        return Deletions(
            names,
            if (lostKept == 0) null
            else "the remote still has $lostKept pack${if (one) "" else "s"} this server's repository lost, so ${if (one) "it was" else "they were"} kept there, with the remote's index files. Backups that need ${if (one) "it" else "them"} can be restored from the remote (java -jar xBackup.jar restore <id> --remote <name>).",
        )
    }

    /**
     * Finds (and with [apply], removes) what the remote keeps that this repository doesn't: see
     * [Maintenance.tidyRemote]. Copies first, so the remote has everything here before anything
     * there goes.
     */
    @JvmStatic
    public fun tidy(local: Repository, to: Backend, apply: Boolean, cancelled: () -> Boolean, progress: (String) -> Unit): Maintenance.Tidy {
        checkSameRepository(local.backend, to)
        if (apply) push(local, to, cancelled, progress)
        progress("looking for what only the remote has")
        return Maintenance(local, cancelled).tidyRemote(to, apply)
    }

    /**
     * Refuses to copy onto a remote that holds a different repository. Copying deletes what the
     * source removed, so after the local repository was lost and started again, copying would
     * otherwise wipe the remote's backups: the very copy that's needed.
     */
    internal fun checkSameRepository(from: Backend, to: Backend) {
        val local = repositoryId(from)
        val remote = try {
            repositoryId(to)
        } catch (e: NoSuchFileException) {
            // A new, empty remote: fine.
            for (prefix in listOf("snapshots/", "data/", "index/", "keep/")) {
                if (to.list(prefix).isNotEmpty()) {
                    throw java.io.IOException("${to.describe()} holds backup data but no repository config, so it isn't overwritten. Point the remote at an empty folder, or restore from it first.")
                }
            }
            return
        }
        if (remote != local) {
            throw java.io.IOException(
                "${to.describe()} holds a different repository (id $remote, this one is $local), so it isn't touched: copying would delete its backups. If this server's repository was lost, restore from the remote (java -jar xBackup.jar restore latest --remote <name>), or point the remote at an empty folder.",
            )
        }
    }

    private fun repositoryId(backend: Backend): String {
        val config = backend.get("config").use { readUpTo(it, 64 * 1024) }
        for (line in String(config, Charsets.UTF_8).split('\n')) if (line.startsWith("id=")) return line.substring(3).trim()
        throw java.io.IOException("${backend.describe()} has a repository config without an id.")
    }

    // Where a file goes in the order, or -1 if it isn't a repository file.
    private fun rank(name: String): Int {
        for ((i, prefix) in ORDER.withIndex()) {
            val matches = if (prefix.endsWith("/")) name.startsWith(prefix) && name.indexOf(".part-") < 0 else name == prefix
            if (matches) return i
        }
        return -1
    }

    private fun percent(done: Long, total: Long): String = if (total == 0L) "100%" else "${done * 100 / total}%"
}

/**
 * Restoring straight from a remote: rather than one request per blob, the files a restore needs
 * are downloaded whole into a local cache with the repository's layout, which is then restored
 * from like any local repository. First the small files and the packs holding the snapshots'
 * structure, then only the data packs the chosen paths use.
 */
public object Fetch {
    /** Copies everything but the data packs into [cache], and opens it. */
    @JvmStatic
    public fun metadata(remote: Backend, cache: Path, progress: (String) -> Unit): Repository {
        Files.createDirectories(cache)
        val local = LocalBackend(cache)
        progress("reading the remote's index")
        val present = HashSet<Hash>()
        for (file in remote.list("")) {
            val name = file.name
            if (name.startsWith("data/")) PackFormat.idOf(name)?.let { present += it }
            if (name == "config" || name.startsWith("index/") || name.startsWith("snapshots/") || name.startsWith("keep/")) copy(remote, local, name, file.size)
        }
        val repository = Repository.open(local, cache.resolve("tmp"), false)
        // The remote can keep index files that list packs it no longer has (kept for packs this
        // server lost); only the packs it has are used.
        repository.keepPacks(present)
        val packs = HashSet<Hash>()
        repository.index().forEach { if (it.entry.type.isMetadata) packs += it.pack }
        download(remote, local, packs, "structure", progress)
        return repository
    }

    /** Downloads the data packs [items] use into the cache repository. */
    @JvmStatic
    public fun data(remote: Backend, cached: Repository, items: List<Restore.Item>, progress: (String) -> Unit) {
        val packs = HashSet<Hash>()
        val index = cached.index()
        val seen = HashSet<Hash>()
        for (item in items) collect(cached, item.node, index, packs, seen)
        download(remote, cached.backend as LocalBackend, packs, "data", progress)
    }

    private fun collect(repository: Repository, node: Tree.Node, index: Index, packs: MutableSet<Hash>, seen: MutableSet<Hash>) {
        when (node.kind) {
            Tree.Kind.DIR -> if (seen.add(node.ref!!)) for (child in repository.tree(node.ref).nodes) collect(repository, child, index, packs, seen)
            Tree.Kind.REGION -> {
                val layout = repository.region(node.ref!!)
                for (i in 0 until RegionLayout.CHUNKS) layout.blob(i)?.let { blob -> index.find(blob)?.let { packs += it.pack } }
            }
            Tree.Kind.FILE, Tree.Kind.SQLITE -> for (blob in node.blobs) index.find(blob)?.let { packs += it.pack }
            Tree.Kind.LINK -> Unit
        }
    }

    private fun download(remote: Backend, local: LocalBackend, packs: Set<Hash>, what: String, progress: (String) -> Unit) {
        var done = 0
        for (pack in packs) {
            val name = PackFormat.fileName(pack)
            if (Files.exists(local.root.resolve(name))) {
                done++
                continue
            }
            progress("downloading $what: $done of ${packs.size} packs")
            copy(remote, local, name, -1)
            done++
        }
    }

    private fun copy(remote: Backend, local: LocalBackend, name: String, size: Long) {
        val temp = local.newTemp(".part")
        try {
            val copied = remote.get(name).use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
            if (size >= 0 && copied != size) throw java.io.IOException("$name came down as $copied bytes, not $size.")
            local.put(name, temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
