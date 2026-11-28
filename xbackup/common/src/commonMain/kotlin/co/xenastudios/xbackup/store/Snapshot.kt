package co.xenastudios.xbackup.store

import kotlin.time.Instant

/**
 * One backup: when it was taken, the tree it saved, and what it cost. Written last, as
 * `snapshots/<id>`, once everything it points to is safely stored, so a snapshot that exists is
 * always whole. Plain text, one `key=value` a line, so it can be read by eye in an emergency.
 *
 * 2.0 adds `external` lines (data the backup can't hold, such as a plugin's MySQL database), which
 * 1.x reads past, as it does any key it doesn't know.
 *
 * @param id the SHA-256 of the file; `null` until written
 * @param parent the snapshot it was compared against, or `null`
 * @param files how many files it holds
 * @param bytes the size of those files
 * @param added how many new bytes it stored (after deduplication and compression)
 * @param pauseMillis how long the server's thread spent saving for it
 * @param autosaveOffMillis how long autosave was off for it
 * @param warnings what it couldn't do, such as a database it couldn't copy safely
 * @param external data that isn't in it, and where it is: `xMoney: MySQL at db:3306/mc`
 */
public data class Snapshot(
    val id: Hash?,
    val time: Instant,
    val finished: Instant,
    val tree: Hash,
    val parent: Hash?,
    val reason: String,
    val files: Long,
    val bytes: Long,
    val added: Long,
    val pauseMillis: Long,
    val autosaveOffMillis: Long,
    val source: String,
    val warnings: List<String>,
    val external: List<String> = emptyList(),
) {
    /** The id, once written. */
    public val key: Hash get() = id ?: error("the snapshot isn't written yet")

    public val durationMillis: Long get() = finished.toEpochMilliseconds() - time.toEpochMilliseconds()

    public fun withId(newId: Hash?): Snapshot = copy(id = newId)

    public fun encode(): ByteArray {
        val out = StringBuilder()
        line(out, HEADER, VERSION.toString())
        line(out, "time", iso(time))
        line(out, "finished", iso(finished))
        line(out, "tree", tree.hex())
        if (parent != null) line(out, "parent", parent.hex())
        line(out, "reason", reason)
        line(out, "files", files.toString())
        line(out, "bytes", bytes.toString())
        line(out, "added", added.toString())
        line(out, "pause-ms", pauseMillis.toString())
        line(out, "autosave-off-ms", autosaveOffMillis.toString())
        line(out, "source", source)
        for (warning in warnings) line(out, "warning", warning)
        for (item in external) line(out, "external", item)
        return out.toString().encodeToByteArray()
    }

    public companion object {
        internal const val VERSION: Int = 1
        private const val HEADER = "xbackup-snapshot"

        /** Where snapshots live: `snapshots/ab12...`. */
        public fun fileName(id: Hash): String = "snapshots/" + id.hex()

        /**
         * An instant as Java's `Instant.toString` writes it (`2026-11-27T10:15:30.123Z`), which
         * 1.x parses: UTC, with as many fraction digits as it needs in groups of three.
         */
        public fun iso(time: Instant): String {
            val millis = time.toEpochMilliseconds()
            val whole = Instant.fromEpochMilliseconds(millis - millis.mod(1000L))
            val text = whole.toString() // 2026-11-27T10:15:30Z
            val fraction = millis.mod(1000L)
            return if (fraction == 0L) text else text.removeSuffix("Z") + "." + fraction.toString().padStart(3, '0') + "Z"
        }

        private fun line(out: StringBuilder, key: String, value: String) {
            out.append(key).append('=').append(escape(value)).append('\n')
        }

        private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

        private fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    out.append(
                        when (val next = value[++i]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> next
                        },
                    )
                } else {
                    out.append(c)
                }
                i++
            }
            return out.toString()
        }

        public fun decode(id: Hash, bytes: ByteArray): Snapshot {
            val what = "snapshot " + id.shortHex()
            val values = LinkedHashMap<String, String>()
            val warnings = ArrayList<String>()
            val external = ArrayList<String>()
            for (line in bytes.decodeToString().split('\n')) {
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                if (eq <= 0) throw CorruptException("$what has a line that isn't key=value")
                val key = line.substring(0, eq)
                val value = unescape(line.substring(eq + 1))
                when (key) {
                    "warning" -> warnings += value
                    "external" -> external += value
                    else -> values[key] = value
                }
            }
            val version = values[HEADER] ?: throw CorruptException("$what isn't a snapshot")
            if (version != VERSION.toString()) throw CorruptException("$what is snapshot version $version; this xBackup reads $VERSION")
            try {
                return Snapshot(
                    id, Instant.parse(required(values, "time", what)), Instant.parse(required(values, "finished", what)),
                    Hash.parse(required(values, "tree", what)), values["parent"]?.let(Hash::parse), values["reason"] ?: "",
                    number(values, "files"), number(values, "bytes"), number(values, "added"), number(values, "pause-ms"),
                    number(values, "autosave-off-ms"), values["source"] ?: "", warnings, external,
                )
            } catch (e: IllegalArgumentException) {
                throw CorruptException("$what: ${e.message}", e)
            }
        }

        private fun required(values: Map<String, String>, key: String, what: String): String =
            values[key] ?: throw CorruptException("$what has no $key")

        private fun number(values: Map<String, String>, key: String): Long = values[key]?.toLong() ?: 0
    }
}
