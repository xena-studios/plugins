package co.xenastudios.xvanish

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.ImportPage
import co.xenastudios.core.common.admin.Importer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/xvanish import supervanish` and `premiumvanish`: who was vanished, from their `data.yml`
 * (`InvisiblePlayers:`, a list of uuids), into `vanished.txt`. Their other settings are theirs and
 * stay; nothing else is kept per player. Read with a line scan, not a YAML parser: the list is all
 * that's needed, and an odd file then can't stop the import.
 */
public class VanishImporter(
    id: String,
    displayName: String,
    /** Their `data.yml`. */
    private val file: Path,
    private val plugin: XVanishPlugin,
) : Importer<PlayerRef>(id, displayName) {
    override fun detect(): Pending<Boolean> = pendingOf(Files.isRegularFile(file))

    override fun count(): Pending<Outcome<Long, String>> = pendingOf(read().let { r -> if (r is Outcome.Ok) Outcome.Ok(r.value.size.toLong()) else r as Outcome.Err })

    override fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<PlayerRef>, String>> = pendingOf(
        when (val all = read()) {
            is Outcome.Ok -> {
                val from = cursor?.toIntOrNull() ?: 0
                val page = all.value.drop(from).take(limit)
                val next = from + page.size
                Outcome.Ok(ImportPage(page, if (next < all.value.size) next.toString() else null))
            }
            is Outcome.Err -> all
        },
    )

    override fun describe(record: PlayerRef): String = "$displayName's vanished player $record"

    override fun write(record: PlayerRef): Pending<Outcome<Unit, String>> {
        plugin.remembered.add(record, plugin.server.name(record) ?: "")
        return pendingOf(Outcome.Ok(Unit))
    }

    /** The uuids under `InvisiblePlayers:`, in either of YAML's list styles. */
    internal fun read(): Outcome<List<PlayerRef>, String> {
        val lines = try {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines()
        } catch (e: Exception) {
            return Outcome.Err("couldn't read ${file.fileName}: $e")
        }
        return Outcome.Ok(invisible(lines))
    }

    public companion object {
        /** Both importers, for a server whose plugins folder is [plugins]. */
        @JvmStatic
        public fun all(plugins: Path, plugin: XVanishPlugin): List<Importer<*>> = listOf(
            VanishImporter("supervanish", "SuperVanish", plugins.resolve("SuperVanish").resolve("data.yml"), plugin),
            VanishImporter("premiumvanish", "PremiumVanish", plugins.resolve("PremiumVanish").resolve("data.yml"), plugin),
        )

        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** The uuids listed under a top-level `InvisiblePlayers:` key. */
        internal fun invisible(lines: List<String>): List<PlayerRef> {
            val out = LinkedHashSet<PlayerRef>()
            var inside = false
            for (line in lines) {
                if (line.startsWith("InvisiblePlayers:")) {
                    // The flow style: InvisiblePlayers: [a, b]
                    UUID.findAll(line).mapNotNull { PlayerRef.parseOrNull(it.value.lowercase()) }.forEach { out += it }
                    inside = true
                    continue
                }
                if (!inside) continue
                val trimmed = line.trim()
                if (trimmed.startsWith("-")) {
                    UUID.find(trimmed)?.let { PlayerRef.parseOrNull(it.value.lowercase()) }?.let { out += it }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    inside = false
                }
            }
            return out.toList()
        }
    }
}
