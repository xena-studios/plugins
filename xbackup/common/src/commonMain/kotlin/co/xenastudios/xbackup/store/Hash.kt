package co.xenastudios.xbackup.store

/**
 * A SHA-256, the name of everything in a repository: a blob is named by the hash of its content
 * before compression; a pack, index or snapshot file by the hash of its bytes as stored. Kept as
 * four longs, so it's cheap to compare and to keep millions of. The repository format is 1.x's,
 * byte for byte (decisions/2026-11-18-xbackup.md): either version reads what the other wrote.
 */
public class Hash(internal val a: Long, internal val b: Long, internal val c: Long, internal val d: Long) : Comparable<Hash> {

    /** The 32 bytes, big-endian. */
    public fun bytes(): ByteArray {
        val out = ByteArray(BYTES)
        putLong(out, 0, a)
        putLong(out, 8, b)
        putLong(out, 16, c)
        putLong(out, 24, d)
        return out
    }

    /** 64 lowercase hex digits. */
    public fun hex(): String {
        val out = StringBuilder(BYTES * 2)
        for (v in longArrayOf(a, b, c, d)) {
            for (shift in 60 downTo 0 step 4) out.append(DIGITS[((v ushr shift) and 0xF).toInt()])
        }
        return out.toString()
    }

    /** The first 8 hex digits, as people see snapshot ids. */
    public fun shortHex(): String = hex().substring(0, 8)

    /** The first 8 bytes, for compact sets where a rare false match is harmless. */
    public val prefix: Long get() = a

    override fun compareTo(other: Hash): Int {
        var r = a.toULong().compareTo(other.a.toULong())
        if (r == 0) r = b.toULong().compareTo(other.b.toULong())
        if (r == 0) r = c.toULong().compareTo(other.c.toULong())
        if (r == 0) r = d.toULong().compareTo(other.d.toULong())
        return r
    }

    override fun equals(other: Any?): Boolean = other is Hash && a == other.a && b == other.b && c == other.c && d == other.d

    override fun hashCode(): Int = (a xor (a ushr 32)).toInt()

    override fun toString(): String = hex()

    public companion object {
        public const val BYTES: Int = 32
        private const val DIGITS = "0123456789abcdef"

        /** The hash from a 32-byte digest. */
        public fun of(digest: ByteArray): Hash {
            require(digest.size == BYTES) { "a hash is 32 bytes, not ${digest.size}" }
            return read(digest, 0)
        }

        /** 32 bytes of [bytes] from [offset]. */
        public fun read(bytes: ByteArray, offset: Int): Hash =
            Hash(getLong(bytes, offset), getLong(bytes, offset + 8), getLong(bytes, offset + 16), getLong(bytes, offset + 24))

        /** Reads 64 hex digits. */
        public fun parse(hex: String): Hash {
            require(isHex(hex)) { "not a hash: $hex" }
            val longs = LongArray(4)
            for (i in 0 until 4) {
                var v = 0L
                for (j in 0 until 16) v = (v shl 4) or DIGITS.indexOf(hex[i * 16 + j]).toLong()
                longs[i] = v
            }
            return Hash(longs[0], longs[1], longs[2], longs[3])
        }

        /** Whether [text] is 64 lowercase hex digits. */
        public fun isHex(text: String): Boolean {
            if (text.length != BYTES * 2) return false
            for (ch in text) if (!(ch in '0'..'9' || ch in 'a'..'f')) return false
            return true
        }

        internal fun putLong(out: ByteArray, at: Int, value: Long) {
            for (i in 0 until 8) out[at + i] = (value ushr (56 - 8 * i)).toByte()
        }

        internal fun getLong(bytes: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
            return v
        }
    }
}

/**
 * What a blob holds. Data blobs (file content, region chunks) and metadata blobs (trees, region
 * layouts) go into separate packs, so reading a snapshot's structure never means fetching its data.
 */
public enum class BlobType {
    /** File content, or one chunk's record from a region file. */
    DATA,

    /** A directory listing. */
    TREE,

    /** A region file's layout: where each chunk sits, its timestamp, and its blob. */
    REGION;

    /** Whether it's structure rather than content. */
    public val isMetadata: Boolean get() = this != DATA

    public companion object {
        private val VALUES = entries.toTypedArray()

        internal fun of(code: Int): BlobType {
            if (code < 0 || code >= VALUES.size) throw CorruptException("unknown blob type $code")
            return VALUES[code]
        }
    }
}

/**
 * Where one blob sits in its pack.
 *
 * @param storedLength its bytes in the pack, compressed or not
 * @param rawLength its bytes once inflated: the content its id is the hash of
 */
public data class PackEntry(
    val id: Hash,
    val type: BlobType,
    val compressed: Boolean,
    val offset: Long,
    val storedLength: Int,
    val rawLength: Int,
)
