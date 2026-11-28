package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xstats.data.PlayerRecord
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.stat.Vanilla
import co.xenastudios.xstats.store.Store
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads players' history from the world's stats files, which Minecraft keeps whatever plugins are
 * installed: `<world>/players/stats/<uuid>.json` on 26.x, `<world>/stats/<uuid>.json` before. Only
 * reads; never on a server thread. 1.13 and later's format (categories); older files are skipped.
 */
public object StatsFiles {
    /** Files bigger than this aren't stats files. A busy player's is a few tens of KiB. */
    internal const val MAX_BYTES: Long = 4L * 1024 * 1024

    /** Each player's newest file, found without reading any, and how many files weren't players'. */
    public class Index(public val files: List<Pair<PlayerRef, Path>>, public val skipped: Int)

    /** Some players read from their files, and how many files couldn't be. */
    public class Part(public val players: List<Store.Imported>, public val skipped: Int)

    /** The folder with the world's level.dat, where players' files are kept. On 26.x the main world's own folder is three below it. */
    public fun levelRoot(world: Path): Path {
        var at: Path? = world.toAbsolutePath().normalize()
        var up = 0
        while (up <= 3 && at != null) {
            if (Files.isRegularFile(at.resolve("level.dat"))) return at
            at = at.parent
            up++
        }
        return world
    }

    /** The stats folders a world can have, newest layout first. */
    public fun folders(world: Path): List<Path> {
        val root = levelRoot(world)
        return listOf(root.resolve("players").resolve("stats"), root.resolve("stats")).filter { Files.isDirectory(it) }
    }

    /** Every player's file in the folders. A player in both (a world moved to 26.x keeps the old folder until each player joins) is read from the newer file. */
    public fun index(folders: List<Path>): Index {
        val files = LinkedHashMap<PlayerRef, Path>()
        var skipped = 0
        for (folder in folders) {
            try {
                Files.newDirectoryStream(folder, "*.json").use { stream ->
                    for (file in stream) {
                        val id = uuid(file.fileName.toString())
                        if (id == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                            skipped++
                            continue
                        }
                        val known = files[id]
                        if (known == null || Files.getLastModifiedTime(file) > Files.getLastModifiedTime(known)) files[id] = file
                    }
                }
            } catch (e: IOException) {
                skipped++
            }
        }
        return Index(files.entries.map { it.key to it.value }, skipped)
    }

    /** Reads some players' files. [names] gives a player's last known name, or `null`. */
    public fun readPart(files: List<Pair<PlayerRef, Path>>, stats: List<StatDef>, names: (PlayerRef) -> String?): Part {
        var skipped = 0
        val players = ArrayList<Store.Imported>()
        for ((id, file) in files) {
            try {
                val parsed = parse(file)
                if (parsed == null) {
                    skipped++
                    continue
                }
                val values = HashMap<String, Long>()
                val counters = HashMap<String, Boolean>()
                for (stat in stats) {
                    val value = value(stat, parsed) ?: continue
                    if (value > 0) {
                        values[stat.id] = value
                        counters[stat.id] = stat.source is StatDef.Vanillas
                    }
                }
                if (values.isNotEmpty()) {
                    // The file is saved as the player leaves: when they were last seen, near enough.
                    players += Store.Imported(id, names(id), Files.getLastModifiedTime(file).toMillis(), values, counters)
                }
            } catch (e: IOException) {
                skipped++
            } catch (e: RuntimeException) {
                skipped++
            }
        }
        return Part(players, skipped)
    }

    /** A stat's value from a parsed file, or `null` if the file can't give it. */
    internal fun value(stat: StatDef, parsed: Map<String, Map<String, Long>>): Long? = when (val source = stat.source) {
        is StatDef.Vanillas -> source.terms.sumOf { Vanilla.fromFile(it, parsed) }
        // Before xStats, playtime counted AFK time too: the best history there is.
        is StatDef.Tracked -> Vanilla.fromFile(Vanilla.term(if (source.playtime) "play_time" else "leave_game"), parsed)
        else -> null
    }

    /** Names by uuid from the server's `usercache.json`, which remembers every player who joined recently. Empty if it can't be read. */
    public fun userCache(file: Path): Map<PlayerRef, String> {
        val names = HashMap<PlayerRef, String>()
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 64L * 1024 * 1024) return names
            val root = MiniJson.parse(String(Files.readAllBytes(file), StandardCharsets.UTF_8)) as? List<*> ?: return names
            for (entry in root) {
                val fields = entry as? Map<*, *> ?: continue
                val id = (fields["uuid"] as? String)?.let { PlayerRef.parseOrNull(it) } ?: continue
                val name = fields["name"] as? String ?: continue
                // Bedrock players' names (Floodgate's .Steve) too, not only Java ones.
                if (PlayerRecord.NAME.matches(name)) names[id] = name
            }
        } catch (e: IOException) {
            names.clear()
        } catch (e: RuntimeException) {
            names.clear()
        }
        return names
    }

    private fun uuid(name: String): PlayerRef? = if (!name.endsWith(".json")) null else PlayerRef.parseOrNull(name.removeSuffix(".json"))?.takeIf {
        // Only dashed uuids, as Minecraft writes them.
        name.length == 41
    }

    /** A file's `stats` object as category to key to value, or `null` if it isn't a stats file from 1.13 or later. */
    internal fun parse(file: Path): Map<String, Map<String, Long>>? {
        if (Files.size(file) > MAX_BYTES) return null
        val root = MiniJson.parse(String(Files.readAllBytes(file), StandardCharsets.UTF_8)) as? Map<*, *> ?: return null
        val stats = root["stats"] as? Map<*, *> ?: return null
        val out = HashMap<String, Map<String, Long>>()
        for ((category, values) in stats) {
            val map = values as? Map<*, *> ?: continue
            val read = HashMap<String, Long>()
            for ((key, value) in map) {
                val number = (value as? MiniJson.Number)?.toLong() ?: continue
                read[key.toString()] = number
            }
            out[category.toString()] = read
        }
        return out
    }
}

/** Just enough JSON for stats files and usercache.json: objects, arrays, strings, numbers, true, false and null. */
public object MiniJson {
    /** A number, as written. */
    public class Number(public val text: String) {
        public fun toLong(): Long? = text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it == kotlin.math.floor(it) && kotlin.math.abs(it) < 9.2e18 }?.toLong()
    }

    public class Malformed(message: String) : RuntimeException(message)

    public fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value(0)
        reader.space()
        if (reader.at < text.length) throw Malformed("text after the value at ${reader.at}")
        return value
    }

    private class Reader(val text: String) {
        var at = 0

        fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(depth: Int): Any? {
            if (depth > 64) throw Malformed("nested too deep")
            space()
            if (at >= text.length) throw Malformed("ended early")
            return when (val c = text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw Malformed("unexpected '$c' at $at")
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            at++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (at < text.length && text[at] == '}') {
                at++
                return out
            }
            while (true) {
                space()
                if (at >= text.length || text[at] != '"') throw Malformed("expected a key at $at")
                val key = string()
                space()
                if (at >= text.length || text[at] != ':') throw Malformed("expected : at $at")
                at++
                out[key] = value(depth + 1)
                space()
                if (at >= text.length) throw Malformed("ended early")
                when (text[at++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw Malformed("expected , or } at ${at - 1}")
                }
            }
        }

        fun array(depth: Int): List<Any?> {
            at++
            val out = ArrayList<Any?>()
            space()
            if (at < text.length && text[at] == ']') {
                at++
                return out
            }
            while (true) {
                out += value(depth + 1)
                space()
                if (at >= text.length) throw Malformed("ended early")
                when (text[at++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw Malformed("expected , or ] at ${at - 1}")
                }
            }
        }

        fun string(): String {
            at++
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (at >= text.length) break
                        when (val e = text[at++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (at + 4 > text.length) throw Malformed("a short \\u escape")
                                out.append(text.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw Malformed("a string that never ends")
        }

        fun number(): Number {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return Number(text.substring(start, at))
        }

        fun word(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw Malformed("unexpected text at $at")
            at += word.length
            return value
        }
    }
}
