package co.xenastudios.xbackup.store

import kotlin.math.roundToLong

/** Sizes as people write them: `512MB`, `2GB`, `1.5TB`. Units are powers of 1024. */
public object Sizes {
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** A short form: `1.4 GB`, `812 KB`, `0 B`. */
    public fun format(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.size - 1) {
            value /= 1024
            unit++
        }
        val digits = if (value >= 100) 0 else if (value >= 10) 1 else 2
        return fixed(value, digits) + " " + UNITS[unit]
    }

    // value to [digits] decimals, rounded half up, as Java's %.Nf does for these numbers.
    private fun fixed(value: Double, digits: Int): String {
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val rounded = (value * scale).roundToLong()
        if (digits == 0) return rounded.toString()
        val whole = rounded / scale
        val fraction = (rounded % scale).toString().padStart(digits, '0')
        return "$whole.$fraction"
    }

    /**
     * Reads a size. A plain number is bytes; units are B, KB, MB, GB and TB (K, M, G and T too),
     * in any case. `0` and `off` are 0.
     *
     * @throws IllegalArgumentException with a message for the owner, if it isn't one
     */
    public fun parse(text: String): Long {
        val input = text.trim().uppercase().replace(" ", "")
        if (input.isEmpty() || input == "OFF" || input == "NONE") return 0
        var end = 0
        while (end < input.length && (input[end].isDigit() || input[end] == '.')) end++
        val bad = "\"$text\" isn't a size. Write a number and a unit: 500MB, 20GB."
        require(end > 0) { bad }
        val number = input.substring(0, end).toDoubleOrNull() ?: throw IllegalArgumentException(bad)
        var unit = input.substring(end)
        if (unit.endsWith("IB")) unit = unit.substring(0, unit.length - 2) + "B"
        val scale = when (unit) {
            "", "B" -> 1L
            "K", "KB" -> 1L shl 10
            "M", "MB" -> 1L shl 20
            "G", "GB" -> 1L shl 30
            "T", "TB" -> 1L shl 40
            else -> throw IllegalArgumentException("\"$text\" has an unknown unit. Use KB, MB, GB or TB.")
        }
        val bytes = number * scale
        require(bytes <= Long.MAX_VALUE / 2) { "\"$text\" is too big." }
        return bytes.roundToLong()
    }
}

/**
 * Where a moment falls in the owner's time zone, for [Retention]'s tiers: any value that's the
 * same for two moments in the same hour (day, ISO week, month) and different otherwise. On the
 * JVM, the server's time zone.
 */
public interface Calendar {
    public fun hour(epochMillis: Long): Long

    public fun day(epochMillis: Long): Long

    public fun week(epochMillis: Long): Long

    public fun month(epochMillis: Long): Long
}

/**
 * Which snapshots to keep: the newest few, then the newest of each of the last so many hours,
 * days, weeks and months. "24 hourly, 7 daily" keeps a backup for each of the last 24 hours that
 * have one, and for each of the last 7 days that have one; a snapshot can count for several. The
 * newest snapshot and pinned ones are always kept.
 *
 * @param last the newest this many, whatever their age
 */
public data class Retention(val last: Int, val hourly: Int, val daily: Int, val weekly: Int, val monthly: Int) {

    /** The snapshots to keep, by id. */
    public fun keep(snapshots: List<Snapshot>, pinned: Set<Hash>, calendar: Calendar): Set<Hash> {
        val newestFirst = snapshots.sortedByDescending { it.time }
        val keep = HashSet<Hash>()
        if (newestFirst.isEmpty()) return keep
        keep += newestFirst[0].key
        for (snapshot in newestFirst) if (snapshot.key in pinned) keep += snapshot.key
        for (i in 0 until minOf(last, newestFirst.size)) keep += newestFirst[i].key
        bucket(newestFirst, hourly, keep) { calendar.hour(it) }
        bucket(newestFirst, daily, keep) { calendar.day(it) }
        bucket(newestFirst, weekly, keep) { calendar.week(it) }
        bucket(newestFirst, monthly, keep) { calendar.month(it) }
        return keep
    }

    // Keeps the newest snapshot in each of the newest `count` buckets that have one.
    private inline fun bucket(newestFirst: List<Snapshot>, count: Int, keep: MutableSet<Hash>, key: (Long) -> Long) {
        var last: Long? = null
        var kept = 0
        for (snapshot in newestFirst) {
            if (kept >= count) return
            val bucket = key(snapshot.time.toEpochMilliseconds())
            if (bucket != last) {
                keep += snapshot.key
                last = bucket
                kept++
            }
        }
    }
}

/**
 * Glob patterns over `/`-separated paths, the same on every system: `*` is anything but `/`,
 * `**` is anything, `?` one character, and `{a,b}` either. `**` + `/` also matches nothing, so
 * `**` + `/x` matches `x` at the top too.
 */
public object Globs {
    /** The pattern as a regex. @throws IllegalArgumentException if it can't be used. */
    public fun regex(glob: String): Regex {
        val regex = StringBuilder()
        var braces = 0
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            if (c == '*' && i + 1 < glob.length && glob[i + 1] == '*') {
                i++
                if (i + 1 < glob.length && glob[i + 1] == '/') {
                    i++
                    regex.append("(?:.*/)?")
                } else {
                    regex.append(".*")
                }
            } else if (c == '*') {
                regex.append("[^/]*")
            } else if (c == '?') {
                regex.append("[^/]")
            } else if (c == '{') {
                braces++
                regex.append("(?:")
            } else if (c == '}' && braces > 0) {
                braces--
                regex.append(')')
            } else if (c == ',' && braces > 0) {
                regex.append('|')
            } else {
                regex.append(Regex.escape(c.toString()))
            }
            i++
        }
        require(braces == 0) { "\"$glob\" has a { without a }" }
        return Regex(regex.toString())
    }

    /** Checks a pattern from the config, so a bad one is reported once, as it loads. */
    public fun check(pattern: String) {
        val glob = pattern.trim().replace('\\', '/')
        require(glob.isNotEmpty()) { "an empty pattern" }
        regex(glob)
    }

    /**
     * A plugin's excludes for its own source, made to apply only inside it: a pattern that starts
     * with the source's path stays as it is, and any other is read from the source, so `**` +
     * `/` + `*.log` leaves out that plugin's logs, never every log on the server.
     */
    public fun scoped(source: String?, excludes: List<String>): List<String> {
        if (source.isNullOrEmpty()) return emptyList()
        val scoped = ArrayList<String>()
        for (pattern in excludes) {
            var clean = pattern.trim().replace('\\', '/')
            while (clean.startsWith("/")) clean = clean.substring(1)
            if (clean.isEmpty()) continue
            scoped += if (clean == source || clean.startsWith("$source/")) clean else "$source/$clean"
        }
        return scoped
    }
}

/** Paths as typed in `/backup restore`: `./world/` is `world`, and `..` is refused. */
public object RestorePaths {
    /** @throws IllegalArgumentException if it goes up with `..` */
    public fun normalise(path: String): String {
        var clean = path.trim().replace('\\', '/')
        while (clean.startsWith("./")) clean = clean.substring(2)
        while (clean.startsWith("/")) clean = clean.substring(1)
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length - 1)
        if (clean == ".") return ""
        for (part in clean.split('/')) require(part != "..") { "A restore path can't go up with \"..\"." }
        return clean
    }
}
