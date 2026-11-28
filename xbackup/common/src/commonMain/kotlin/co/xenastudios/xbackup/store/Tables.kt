package co.xenastudios.xbackup.store

import co.xenastudios.core.common.locked

/**
 * Which pack holds each blob, in memory: one table of every blob, built from the index files.
 * Open addressing over parallel arrays (about 65 bytes a blob), since a big world has millions of
 * chunks and a map of objects would cost several times that. Held only while a backup, restore
 * or check runs. Safe from several threads.
 */
public class Index {
    /** A blob's place: its pack and where in it. */
    public class Location(public val pack: Hash, public val entry: PackEntry)

    private val lock = Any()
    private val packIds = ArrayList<Hash>()
    private val packNumbers = HashMap<Hash, Int>()
    private var k0 = LongArray(0)
    private var k1 = LongArray(0)
    private var k2 = LongArray(0)
    private var k3 = LongArray(0)
    private var pack = IntArray(0)
    private var offset = IntArray(0)
    private var stored = IntArray(0)
    private var raw = IntArray(0)
    private var kind = ByteArray(0)
    private var count = 0
    private var mask = 0

    init {
        allocate(1 shl 10)
    }

    private fun allocate(capacity: Int) {
        k0 = LongArray(capacity)
        k1 = LongArray(capacity)
        k2 = LongArray(capacity)
        k3 = LongArray(capacity)
        pack = IntArray(capacity) { -1 }
        offset = IntArray(capacity)
        stored = IntArray(capacity)
        raw = IntArray(capacity)
        kind = ByteArray(capacity)
        mask = capacity - 1
    }

    /** Adds every blob of a pack. A blob already listed keeps its first place. */
    public fun add(packId: Hash, entries: List<PackEntry>): Unit = locked(lock) {
        val number = packNumbers[packId] ?: packIds.size.also {
            packIds += packId
            packNumbers[packId] = it
        }
        for (entry in entries) {
            require(entry.offset <= Int.MAX_VALUE) { "pack $packId is too big" }
            put(entry.id, number, entry.offset.toInt(), entry.storedLength, entry.rawLength, ((entry.type.ordinal shl 1) or (if (entry.compressed) 1 else 0)).toByte())
        }
    }

    private fun put(id: Hash, packNumber: Int, off: Int, storedLength: Int, rawLength: Int, flags: Byte) {
        if ((count + 1) * 4L > (mask + 1) * 3L) grow()
        val slot = slot(id)
        if (pack[slot] >= 0) return
        k0[slot] = id.a
        k1[slot] = id.b
        k2[slot] = id.c
        k3[slot] = id.d
        pack[slot] = packNumber
        offset[slot] = off
        stored[slot] = storedLength
        raw[slot] = rawLength
        kind[slot] = flags
        count++
    }

    private fun grow() {
        val o0 = k0
        val o1 = k1
        val o2 = k2
        val o3 = k3
        val oPack = pack
        val oOffset = offset
        val oStored = stored
        val oRaw = raw
        val oKind = kind
        allocate((mask + 1) * 2)
        count = 0
        for (i in oPack.indices) {
            if (oPack[i] >= 0) put(Hash(o0[i], o1[i], o2[i], o3[i]), oPack[i], oOffset[i], oStored[i], oRaw[i], oKind[i])
        }
    }

    // The slot holding the id, or the empty slot where it would go.
    private fun slot(id: Hash): Int {
        var i = (id.a xor (id.a ushr 29) xor id.b).toInt() and mask
        while (pack[i] >= 0 && !(k0[i] == id.a && k1[i] == id.b && k2[i] == id.c && k3[i] == id.d)) i = (i + 1) and mask
        return i
    }

    public fun has(id: Hash): Boolean = locked(lock) { pack[slot(id)] >= 0 }

    /** Where the blob is, or `null` if no pack has it. */
    public fun find(id: Hash): Location? = locked(lock) {
        val i = slot(id)
        if (pack[i] < 0) null else Location(packIds[pack[i]], entryAt(i))
    }

    private fun entryAt(i: Int): PackEntry = PackEntry(
        Hash(k0[i], k1[i], k2[i], k3[i]), BlobType.entries[kind[i].toInt() shr 1], kind[i].toInt() and 1 != 0,
        offset[i].toLong() and 0xFFFFFFFFL, stored[i], raw[i],
    )

    public val size: Int get() = locked(lock) { count }

    /** Every pack the index lists, including any whose blobs are all listed elsewhere too. */
    public fun packs(): List<Hash> = locked(lock) { packIds.toList() }

    /** Calls [action] with every blob and its pack. Don't change the index meanwhile. */
    public fun forEach(action: (Location) -> Unit): Unit = locked(lock) {
        for (i in pack.indices) if (pack[i] >= 0) action(Location(packIds[pack[i]], entryAt(i)))
    }

    /** The blobs grouped by pack, for writing a fresh index file. */
    public fun byPack(): Map<Hash, List<PackEntry>> = locked(lock) {
        val packs = LinkedHashMap<Hash, MutableList<PackEntry>>()
        for (i in pack.indices) if (pack[i] >= 0) packs.getOrPut(packIds[pack[i]]) { ArrayList() } += entryAt(i)
        packs
    }
}

/**
 * Blob id prefixes (their first 8 bytes) to a small number, in open addressing over two arrays:
 * about 16 bytes a blob, for marking millions of them. Two blobs sharing a prefix share an entry;
 * [putMin] keeps the smaller number, so a clash can only make a blob look newer and kept longer,
 * never deleted early.
 */
public class PrefixMap {
    private var keys = LongArray(1 shl 12)
    private var values = IntArray(1 shl 12) { EMPTY }

    public var size: Int = 0
        private set

    /** Sets the value for [id]'s prefix to [value], or keeps the old one if it's smaller. */
    public fun putMin(id: Hash, value: Int) {
        require(value >= 0) { "values are never negative" }
        if ((size + 1) * 2 > keys.size) grow()
        val i = slot(id.prefix)
        if (values[i] == EMPTY) {
            keys[i] = id.prefix
            values[i] = value
            size++
        } else if (value < values[i]) {
            values[i] = value
        }
    }

    /** The value for [id]'s prefix, or -1. */
    public operator fun get(id: Hash): Int = values[slot(id.prefix)]

    public operator fun contains(id: Hash): Boolean = get(id) != EMPTY

    private fun slot(key: Long): Int {
        val mask = keys.size - 1
        var i = (key xor (key ushr 32)).toInt() and mask
        while (values[i] != EMPTY && keys[i] != key) i = (i + 1) and mask
        return i
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(oldKeys.size * 2) { EMPTY }
        size = 0
        for (i in oldKeys.indices) {
            if (oldValues[i] != EMPTY) {
                val slot = slot(oldKeys[i])
                keys[slot] = oldKeys[i]
                values[slot] = oldValues[i]
                size++
            }
        }
    }

    private companion object {
        const val EMPTY = -1
    }
}

/**
 * Splits a file into pieces at content-defined points (FastCDC's gear hash with normalised
 * chunking), so an insertion near the start of a big file moves only the piece it's in: the rest
 * cut at the same places as before, hash the same, and are stored once.
 *
 * Pieces are 128 KiB to 2 MiB, about 512 KiB on average. A file smaller than the minimum is one
 * piece. The gear table and masks are part of the repository format: changing them would stop new
 * pieces matching old ones.
 */
public class Chunker(private val read: (ByteArray, Int, Int) -> Int) {
    private val buffer = ByteArray(MAX)
    private var length = 0
    private var eof = false

    /** The next piece, or `null` at the end. */
    public fun next(): ByteArray? {
        while (!eof && length < MAX) {
            val n = read(buffer, length, MAX - length)
            if (n < 0) eof = true else length += n
        }
        if (length == 0) return null
        val cut = cut(buffer, length)
        val piece = buffer.copyOf(cut)
        buffer.copyInto(buffer, 0, cut, length)
        length -= cut
        return piece
    }

    public companion object {
        public const val MIN: Int = 128 shl 10
        public const val AVERAGE: Int = 512 shl 10
        public const val MAX: Int = 2 shl 20

        private val GEAR = LongArray(256).also { gear ->
            // SplitMix64 from a fixed seed: the same table everywhere, forever.
            var state = 0x7842_6163_6B75_7031L
            for (i in gear.indices) {
                state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
                var z = state
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
                gear[i] = z xor (z ushr 31)
            }
        }

        // 21 one-bits before the average size (cuts less likely), 17 after (more likely).
        private val MASK_SMALL = mask(21)
        private val MASK_LARGE = mask(17)

        private fun mask(bits: Int): Long = ((1L shl bits) - 1) shl (64 - bits)

        /** Where the first piece of `data[0, length)` ends. */
        public fun cut(data: ByteArray, length: Int): Int {
            if (length <= MIN) return length
            val end = minOf(length, MAX)
            val normal = minOf(end, AVERAGE)
            var fingerprint = 0L
            var i = MIN
            while (i < normal) {
                fingerprint = (fingerprint shl 1) + GEAR[data[i].toInt() and 0xFF]
                if (fingerprint and MASK_SMALL == 0L) return i + 1
                i++
            }
            while (i < end) {
                fingerprint = (fingerprint shl 1) + GEAR[data[i].toInt() and 0xFF]
                if (fingerprint and MASK_LARGE == 0L) return i + 1
                i++
            }
            return end
        }
    }
}
