package co.xenastudios.xbackup.cli

import co.xenastudios.xbackup.remote.Fetch
import co.xenastudios.xbackup.remote.Remotes
import co.xenastudios.xbackup.remote.RestoreSettings
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.restore.ServerCheck
import co.xenastudios.xbackup.restore.Swap
import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.RestorePaths
import co.xenastudios.xbackup.store.SecretText
import co.xenastudios.xbackup.store.Sizes
import co.xenastudios.xbackup.store.Snapshot
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.Tree
import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Random

/**
 * The command-line side of xBackup, for when the server is stopped (or won't start), 1.x's:
 *
 * ```
 * java -jar plugins/xBackup.jar list
 * java -jar plugins/xBackup.jar restore latest
 * java -jar plugins/xBackup.jar restore 3f9a1c2e world --dry-run
 * java -jar plugins/xBackup.jar restore latest --remote offsite
 * java -jar plugins/xBackup.jar verify --full
 * ```
 *
 * Touches no server classes: it runs on a plain JVM, Java 8 or newer, once the jar's Java shim
 * has put the Kotlin stdlib on the class path (`XBackupCli`). On Fabric the jar is in `mods/` and
 * xBackup's folder is `config/xbackup/`; it finds either.
 *
 * @param jar the jar it runs from, if known: which folder the server is, and never restored over
 */
public class Cli(private val out: PrintStream, private val input: BufferedReader, private val jar: Path?) {
    private var server: Path? = null
    private var repositoryFolder: Path? = null
    private var remote: String? = null
    private var to: Path? = null
    private var dryRun = false
    private var yes = false
    private var full = false
    private var allowLinksOutside = false
    private val arguments = ArrayList<String>()

    /** Runs a command; the exit code is 0 on success. */
    public fun run(args: Array<String>): Int {
        try {
            if (!parse(args)) return 2
            if (arguments.isEmpty() || arguments[0] == "help") {
                help()
                return if (arguments.isEmpty()) 2 else 0
            }
            return when (val command = arguments.removeAt(0)) {
                "list" -> list()
                "restore" -> restore()
                "verify" -> verify()
                else -> {
                    out.println("Unknown command \"$command\".")
                    help()
                    2
                }
            }
        } catch (e: Repository.LockedException) {
            out.println("${e.message} Stop the server first, or wait for the backup to finish.")
            return 1
        } catch (e: java.io.IOException) {
            out.println("Couldn't do that: ${e.message}")
            return 1
        } catch (e: IllegalArgumentException) {
            out.println("Couldn't do that: ${e.message}")
            return 1
        }
    }

    private fun parse(args: Array<String>): Boolean {
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--server", "--repo", "--remote", "--to" -> {
                    if (i + 1 >= args.size) {
                        out.println("$arg needs a value.")
                        return false
                    }
                    val value = args[++i]
                    when (arg) {
                        "--server" -> server = Paths.get(value).toAbsolutePath().normalize()
                        "--repo" -> repositoryFolder = Paths.get(value).toAbsolutePath().normalize()
                        "--remote" -> remote = value
                        else -> to = Paths.get(value).toAbsolutePath().normalize()
                    }
                }
                "--dry-run", "-n" -> dryRun = true
                "--yes", "-y" -> yes = true
                "--full" -> full = true
                "--allow-links-outside" -> allowLinksOutside = true
                "--help", "-h" -> arguments.add(0, "help")
                else -> {
                    if (arg.startsWith("--")) {
                        out.println("Unknown option $arg.")
                        return false
                    }
                    arguments += arg
                }
            }
            i++
        }
        if (server == null) server = guessServer()
        return true
    }

    // The jar sits in <server>/plugins/ (or <server>/mods/ on Fabric), normally; otherwise the current folder.
    private fun guessServer(): Path {
        val folder = jar?.toAbsolutePath()?.parent
        val name = folder?.fileName?.toString()
        if (folder?.parent != null && (name == "plugins" || name == "mods")) return folder.parent
        return Paths.get("").toAbsolutePath().normalize()
    }

    private fun help() {
        out.println(
            """
            xBackup's command-line restore. Run it with the server stopped.

              java -jar xBackup.jar list                          list the backups
              java -jar xBackup.jar restore <id|latest> [path...] restore everything, or only these paths
              java -jar xBackup.jar verify [--full]               check the stored data

            Options:
              --dry-run         show what a restore would change, and change nothing
              --yes             don't ask before restoring
              --to <folder>     restore into another folder instead, which must be empty or new
                                (the server can keep running)
              --remote <name>   read from a remote in config.yml instead of the local repository
              --server <folder> the server folder (default: the one this jar's plugins folder is in)
              --repo <folder>   the repository folder (default: from xBackup's config)
              --allow-links-outside
                                restore links that point outside the server folder too;
                                only for links you made yourself

            Paths are relative to the server folder, such as world, world_nether or
            plugins/xRewind. What's there now is moved to xBackup's restore/replaced/ folder.
            """.trimIndent(),
        )
    }

    // xBackup's folder: plugins/xBackup on Bukkit, config/xbackup on Fabric.
    private fun home(): Path {
        val root = server!!
        val bukkit = root.resolve("plugins").resolve("xBackup")
        val fabric = root.resolve("config").resolve("xbackup")
        return if (!Files.isDirectory(bukkit) && Files.isDirectory(fabric)) fabric else bukkit
    }

    private fun context(): Remotes.Context = Remotes.Context(home(), Throttle.NONE, out::println)

    private fun open(cache: Path?): Repository {
        val settings = RestoreSettings.read(home())
        val name = remote
        if (name != null) {
            if (settings == null || name !in settings.remotes) {
                throw IllegalArgumentException("there's no remote named $name in ${home().resolve(RestoreSettings.NAME)}. Start the server once with it in config.yml.")
            }
            settings.secretsProblem?.let { out.println("Couldn't read the passwords in ${home().resolve(SecretText.SECRETS)}: $it") }
            val backend = Remotes.open(name, settings.remotes.getValue(name), context())
            out.println("Reading ${backend.describe()}...")
            return Fetch.metadata(backend, cache!!) { out.println(it) }
        }
        val folder = repositoryFolder ?: settings?.repository ?: home().resolve("repository")
        val backend = LocalBackend(folder)
        require(Repository.exists(backend)) { "there's no xBackup repository in $folder. Use --repo <folder>." }
        return Repository.open(backend, home().resolve("temp"), false)
    }

    private fun remoteBackend(): Backend {
        val settings = RestoreSettings.read(home())!!
        return Remotes.open(remote!!, settings.remotes.getValue(remote!!), context())
    }

    private fun list(): Int {
        val cache = home().resolve("temp").resolve("remote-" + (remote ?: ""))
        try {
            open(cache).use { repository ->
                repository.lock(false).use {
                    val problems = ArrayList<String>()
                    val snapshots = repository.snapshots(problems)
                    val pins = repository.pins()
                    out.println("${snapshots.size} backups in ${repository.backend.describe()}:")
                    for (snapshot in snapshots.asReversed()) {
                        out.println(
                            String.format(
                                "  %s  %s  %-12s %8s  +%s%s", snapshot.key.shortHex(), time(snapshot), snapshot.reason, Sizes.format(snapshot.bytes),
                                Sizes.format(snapshot.added), if (snapshot.key in pins) "  pinned" else "",
                            ),
                        )
                    }
                    problems.forEach { out.println("  ! $it") }
                    return 0
                }
            }
        } finally {
            if (remote != null) Swap.deleteTree(cache)
        }
    }

    private fun verify(): Int {
        require(remote == null) { "verify reads the local repository; copy it back first, or check the remote with its own tools." }
        open(null).use { repository ->
            repository.lock(false).use {
                out.println("Checking ${if (full) "everything" else "the structure and a 10% sample"} in ${repository.backend.describe()}...")
                val report = Maintenance(repository) { false }.check(if (full) 1.0 else 0.1, null, Random())
                out.println("Read back ${report.packsChecked} of ${report.packsTotal} packs (${Sizes.format(report.bytesChecked)}) across ${report.snapshots} backups.")
                if (report.ok) {
                    out.println("All good.")
                    return 0
                }
                report.problems.forEach { out.println("  ! $it") }
                return 1
            }
        }
    }

    private fun restore(): Int {
        if (arguments.isEmpty()) {
            out.println("Which backup? Give its id (from list) or latest.")
            return 2
        }
        val id = arguments.removeAt(0)
        val paths = arguments.map(RestorePaths::normalise).toMutableList()
        if (paths.isEmpty()) paths += ""
        val root = server!!
        val target = to
        val inPlace = target == null
        if (target != null && !dryRun) {
            ServerCheck.notEmpty(target, root)?.let {
                out.println(it)
                return 1
            }
        }
        // An in-place restore holds running.lock from here to the end, so a server started meanwhile
        // (while this downloads, waits for "yes", or stages) stops before loading any world.
        var claim: ServerCheck.Claim? = null
        if (inPlace && !dryRun) {
            claim = ServerCheck.claim(home())
            val running = if (claim == null) "xBackup holds ${home().fileName}/${ServerCheck.MARKER}" else ServerCheck.worldOpen(root, paths)
            if (running != null) {
                claim?.close()
                out.println("The server is running ($running). Stop it first, or restore with /backup restore in game, or into an empty folder with --to.")
                return 1
            }
        }
        val cache = home().resolve("temp").resolve("remote-" + (remote ?: ""))
        try {
            open(cache).use { repository ->
                repository.lock(false).use {
                    val snapshot = find(repository, id)
                    val protect = HashSet<String>()
                    co.xenastudios.xbackup.backup.relative(root, home())?.let { protect += it }
                    jar?.toAbsolutePath()?.let { j -> if (j.startsWith(root)) protect += root.relativize(j).toString().replace('\\', '/') }
                    (repository.backend as? LocalBackend)?.root?.let { r -> if (r.startsWith(root)) protect += root.relativize(r).toString().replace('\\', '/') }
                    val items = Restore.expand(repository, snapshot, paths, if (inPlace) protect else emptySet())
                    out.println("Backup ${snapshot.key.shortHex()} from ${time(snapshot)} (${snapshot.reason}).")
                    if (inPlace) {
                        val plan = Restore.dryRun(repository, items, root)
                        out.println(
                            "Restoring ${describe(paths)} into $root changes ${plan.changed} files" +
                                (if (plan.changedChunks > 0) " (${plan.changedChunks} region chunks)" else "") +
                                ", brings back ${plan.added} and sets aside ${plan.removed} (${plan.files} files, ${Sizes.format(plan.bytes)} in all).",
                        )
                        plan.examples.forEach { out.println("  $it") }
                    } else {
                        out.println("Restoring ${describe(paths)} into $target.")
                    }
                    if (dryRun) {
                        out.println("Dry run: nothing was changed.")
                        return 0
                    }
                    if (!yes && !confirm()) {
                        out.println("Nothing was changed.")
                        return 1
                    }
                    if (remote != null) Fetch.data(remoteBackend(), repository, items) { out.println(it) }
                    if (target != null) {
                        Files.createDirectories(target)
                        val written = Restore.write(repository, items, target, target, allowLinksOutside, Throttle.NONE, { false }) { out.println(it) }
                        out.println("Wrote ${written.files} files (${Sizes.format(written.bytes)}), each checked against its backup, into $target.")
                        return 0
                    }
                    val restoreFolder = home().resolve("restore")
                    if (Swap.pending(restoreFolder) != null) {
                        out.println("A restore staged in game is waiting in $restoreFolder. Start the server to finish it, or delete ${restoreFolder.resolve(Swap.PENDING)} to cancel it, then try again.")
                        return 1
                    }
                    val staged = restoreFolder.resolve(Swap.STAGED)
                    Swap.deleteTree(staged)
                    Files.createDirectories(staged)
                    val written = Restore.write(repository, items, staged, root, allowLinksOutside, Throttle.NONE, { false }) { out.println(it) }
                    out.println("Checked ${written.files} files (${Sizes.format(written.bytes)}). Putting them in place...")
                    val entries = items.map { Swap.Entry(it.path, it.node.kind == Tree.Kind.SQLITE) }
                    // Checked again right before the swap: staging can take hours.
                    ServerCheck.worldOpen(root, paths)?.let {
                        Swap.deleteTree(staged)
                        out.println("A world was opened while the restore was staged ($it), so nothing was changed. Stop the server, then restore again.")
                        return 1
                    }
                    Swap.schedule(restoreFolder, snapshot.key.hex(), entries)
                    val aside = Swap.apply(restoreFolder, root) { out.println("  $it") }
                    out.println("Done. What was there before is in $aside; delete it once you're happy.")
                    return 0
                }
            }
        } finally {
            claim?.close()
            if (remote != null) Swap.deleteTree(cache)
        }
    }

    private fun describe(paths: List<String>): String = if (paths == listOf("")) "everything" else paths.joinToString(", ")

    private fun confirm(): Boolean {
        out.print("Type yes to restore: ")
        out.flush()
        return input.readLine()?.trim().equals("yes", ignoreCase = true)
    }

    private fun time(snapshot: Snapshot): String = TIME.format(java.time.Instant.ofEpochMilli(snapshot.time.toEpochMilliseconds()))

    private fun find(repository: Repository, id: String): Snapshot {
        val snapshots = repository.snapshots(ArrayList())
        require(snapshots.isNotEmpty()) { "the repository has no backups." }
        if (id.equals("latest", ignoreCase = true) || id.equals("last", ignoreCase = true)) return snapshots.last()
        val found = snapshots.filter { it.key.hex().startsWith(id.lowercase()) }
        require(found.size <= 1) { "more than one backup starts with $id; give more of it." }
        require(found.size == 1 && id.length >= 4) { "no backup's id starts with $id. list shows them." }
        return found[0]
    }

    public companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        /** The entry point the jar's Java shim calls, once the stdlib is loaded. */
        @JvmStatic
        public fun main(args: Array<String>, jar: Path?): Int =
            Cli(System.out, BufferedReader(java.io.InputStreamReader(System.`in`, Charsets.UTF_8)), jar).run(args)
    }
}
