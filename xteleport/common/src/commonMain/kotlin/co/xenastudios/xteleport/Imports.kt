package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.api.Warp

/**
 * EssentialsX's homes, warps and spawn, for `/xteleport import essentials`: read from its files
 * by the platform (on the JVM, `EssentialsFiles`), off the server's threads, never changing them.
 */
public interface Essentials {
    /** EssentialsX's folder, as the console names it: `plugins/Essentials`. */
    public val folder: String

    /**
     * Reads EssentialsX's data. [worldName] turns a world's uuid (EssentialsX saves worlds by uuid,
     * with the name beside it in newer versions) or old name into a loaded world's name, or `null`.
     */
    public fun read(worldName: (String) -> String?): Found

    /** What was found, and what couldn't be read (at most 20, in English). */
    public class Found(
        public val homes: List<Home>,
        public val warps: List<Warp>,
        public val spawn: Location?,
        public val players: Int,
        public val problems: List<String>,
    )

    public companion object {
        /** A name xTeleport accepts: other characters become `_`, and it's cut to 32. `null` if nothing's left. */
        public fun cleanName(name: String?): String? {
            if (name == null) return null
            val out = StringBuilder()
            for (c in name.trim()) out.append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') c else '_')
            val clean = if (out.length > 32) out.substring(0, 32) else out.toString()
            return if (clean.isEmpty() || clean.all { it == '_' }) null else clean
        }
    }
}

/** `/xteleport import essentials [confirm]`: what was found, then, once confirmed, the import. 1.x's way: a dry run first. */
internal class Imports(private val plugin: XTeleportPlugin, private val actions: Actions) {
    fun report(source: CommandSource, folder: String, found: Essentials.Found, confirm: Boolean) {
        for (problem in found.problems) actions.reply(source, XTeleportMessages.importProblem, "problem" to problem)
        if (found.homes.isEmpty() && found.warps.isEmpty() && found.spawn == null) {
            actions.reply(source, XTeleportMessages.importNoData, "folder" to folder)
            return
        }
        actions.reply(
            source, XTeleportMessages.importFound, "homes" to found.homes.size, "players" to found.players, "warps" to found.warps.size,
            "spawn" to if (found.spawn == null) XTeleportMessages.importNoSpawn else XTeleportMessages.importASpawn,
        )
        if (!confirm) {
            actions.reply(source, XTeleportMessages.importDryRun)
            return
        }
        val store = plugin.store ?: return actions.reply(source, XTeleportMessages.unavailable)
        store.importAll(found.homes, found.warps, found.spawn, plugin.now()).onSettled { imported, error ->
            if (imported !is Outcome.Ok) {
                val reason = (imported as? Outcome.Err)?.error?.toString() ?: error.toString()
                actions.later(source) { actions.reply(source, XTeleportMessages.importFailed, "reason" to reason) }
                return@onSettled
            }
            // The warps and every online player's homes read again, with what was added.
            store.loadServer().onSettled { server, _ ->
                if (server is Outcome.Ok) plugin.places.load(server.value)
                plugin.homes.forgetAll()
                for (player in plugin.server.online()) plugin.homes.of(player)
                val done = imported.value
                plugin.log.info("Imported ${done.homes} homes and ${done.warps} warps from EssentialsX.")
                actions.later(source) {
                    actions.reply(
                        source, XTeleportMessages.importDone, "homes" to done.homes, "warps" to done.warps,
                        "spawn" to if (done.spawn) XTeleportMessages.importAndTheSpawn else "", "skipped" to done.skipped,
                    )
                }
            }
        }
    }
}
