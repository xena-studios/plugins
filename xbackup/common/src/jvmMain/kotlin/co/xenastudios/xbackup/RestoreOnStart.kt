package co.xenastudios.xbackup

import co.xenastudios.xbackup.restore.ServerCheck
import co.xenastudios.xbackup.restore.Swap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What xBackup does as the server loads it, before any world loads (Bukkit's onLoad, a Fabric
 * mod's initializer): holds `running.lock` until the process exits, so the command-line restore
 * sees the server running, and puts in place a restore staged with `/backup restore`. 1.x's
 * bootstrap (decisions/2026-11-18-xbackup.md, "Restores swap from the bootstrap, all or nothing").
 */
public object RestoreOnStart {
    /** Left for the plugin to report once it's enabled. */
    public const val DONE: String = "last-restore.txt"

    /** What to say, and how loudly. */
    public interface Say {
        public fun info(message: String)

        public fun error(message: String, cause: Throwable?)
    }

    /**
     * @param home xBackup's folder
     * @param root the server folder, which the journal's paths are relative to: the working directory
     */
    @JvmStatic
    @JvmOverloads
    public fun run(home: Path, say: Say, root: Path = Paths.get("").toAbsolutePath().normalize()) {
        // From now until the process exits, the command-line restore sees the server running.
        if (ServerCheck.hold(home) == ServerCheck.Hold.BUSY) {
            // The command-line restore holds it while it stages and swaps: loading the worlds now
            // would load them while their folders are swapped, or run its journal twice.
            say.error(
                "xBackup stopped the server from starting: another process holds ${home.resolve(ServerCheck.MARKER)}. A command-line restore is running on this server folder (or another server is using it). Let it finish, then start the server.",
                null,
            )
            halt()
        }
        val restore = home.resolve("restore")
        if (!Files.exists(restore.resolve(Swap.PENDING))) return
        try {
            val pending = Swap.pending(restore) ?: return
            val id = pending.snapshot.take(8)
            say.info("Putting the restore of backup $id in place (${pending.entries.size} paths)...")
            val start = System.nanoTime()
            val aside = Swap.apply(restore, root) { say.info(it) }
            val done = "Restored backup $id on this start, in ${(System.nanoTime() - start) / 1_000_000} ms. What was there before is in $aside; delete it once you're happy."
            say.info(done)
            Files.write(restore.resolve(DONE), listOf(done), Charsets.UTF_8)
        } catch (e: Swap.StuckException) {
            // Half a world swapped in: starting would load it, or generate a new one beside it, and
            // the next start would think it was finished. Stop here instead.
            say.error("xBackup stopped the server from starting: ${e.message}", e)
            halt()
        } catch (e: Exception) {
            say.error("Couldn't put the restore waiting in $restore in place: ${e.message}" + if (e is Swap.FailedException) "" else " Nothing was changed.", e)
        }
    }

    /** The note [run] left about the restore it put in place, said once and removed. */
    @JvmStatic
    public fun report(home: Path, info: (String) -> Unit) {
        val note = home.resolve("restore").resolve(DONE)
        if (!Files.exists(note)) return
        try {
            Files.readAllLines(note, Charsets.UTF_8).forEach(info)
            Files.delete(note)
        } catch (e: java.io.IOException) {
            info("Couldn't read $note: ${e.message}")
        }
    }

    private fun halt() {
        System.err.flush()
        Runtime.getRuntime().halt(1)
    }
}
