package co.xenastudios.xbackup.store

/**
 * Pack files: blobs end to end, then a footer listing them. A pack is named by the SHA-256 of all
 * its bytes and never changes once written.
 *
 * ```
 * blob bytes ... | footer | footer length (u32) | "XBPF"
 * footer = "XBPK" version(u8) count(varint) { type(u8) flags(u8) id(32) stored(varint) raw(varint) }...
 * ```
 *
 * Offsets aren't stored: each blob starts where the one before it ends. The footer makes every
 * pack self-describing, so the index can always be rebuilt from the packs.
 */
public object PackFormat {
    internal const val VERSION: Int = 1
    private val FOOTER_MAGIC = "XBPK".encodeToByteArray()
    private val TRAILER_MAGIC = "XBPF".encodeToByteArray()
    public const val TRAILER: Int = 8

    /** No blob is bigger than this; a larger length in a footer means damage. */
    public const val MAX_BLOB: Int = 16 shl 20
    private const val FLAG_COMPRESSED = 1

    /** Where packs live: `data/ab/ab12...`. */
    public fun fileName(id: Hash): String {
        val hex = id.hex()
        return "data/" + hex.substring(0, 2) + "/" + hex
    }

    /** The pack id in a file name, or `null` if it isn't a pack's name. */
    public fun idOf(name: String): Hash? {
        if (!name.startsWith("data/") || name.length != 5 + 3 + 64) return null
        val hex = name.substring(8)
        if (!Hash.isHex(hex) || name.substring(5, 7) != hex.substring(0, 2)) return null
        return Hash.parse(hex)
    }

    /** The footer and trailer for [entries], written after their blobs. */
    public fun footer(entries: List<PackEntry>): ByteArray {
        val footer = Codec.Out(16 + entries.size * 44)
        footer.bytes(FOOTER_MAGIC).u8(VERSION).varint(entries.size)
        for (entry in entries) {
            footer.u8(entry.type.ordinal).u8(if (entry.compressed) FLAG_COMPRESSED else 0).hash(entry.id)
                .varint(entry.storedLength).varint(entry.rawLength)
        }
        val bytes = footer.toArray()
        return Codec.Out(bytes.size + TRAILER).bytes(bytes).u32(bytes.size).bytes(TRAILER_MAGIC).toArray()
    }

    /** The footer's length, from a pack's last [TRAILER] bytes. */
    public fun footerLength(trailer: ByteArray, fileSize: Long, name: String): Int {
        val input = Codec.In(trailer, name)
        val footerLength = input.u32()
        if (!input.bytes(4).contentEquals(TRAILER_MAGIC)) throw CorruptException("$name doesn't end like a pack")
        if (footerLength < 6 || footerLength > fileSize - TRAILER) throw CorruptException("$name has a footer length of $footerLength")
        return footerLength
    }

    /** Reads the blob list from a whole pack in memory. */
    public fun parseFooter(pack: ByteArray, name: String): List<PackEntry> {
        if (pack.size < TRAILER) throw CorruptException("$name is too short to be a pack")
        val footerLength = footerLength(pack.copyOfRange(pack.size - TRAILER, pack.size), pack.size.toLong(), name)
        val start = pack.size - TRAILER - footerLength
        return parseFooter(pack, start, footerLength, start.toLong(), name)
    }

    /** Reads the blob list from [length] bytes of footer at [offset] in [bytes]; the blobs end at [blobsEnd]. */
    public fun parseFooter(bytes: ByteArray, offset: Int, length: Int, blobsEnd: Long, name: String): List<PackEntry> {
        val input = Codec.In(bytes, offset, length, "$name's footer")
        if (!input.bytes(4).contentEquals(FOOTER_MAGIC)) throw CorruptException("$name's footer has the wrong magic")
        val version = input.u8()
        if (version != VERSION) throw CorruptException("$name is pack version $version; this xBackup reads $VERSION")
        val count = input.count(length / 35)
        val entries = ArrayList<PackEntry>(count)
        var position = 0L
        repeat(count) {
            val type = BlobType.of(input.u8())
            val flags = input.u8()
            val id = input.hash()
            val stored = input.count(MAX_BLOB)
            val raw = input.count(MAX_BLOB)
            entries += PackEntry(id, type, flags and FLAG_COMPRESSED != 0, position, stored, raw)
            position += stored
        }
        input.expectEnd()
        if (position != blobsEnd) throw CorruptException("$name's footer covers $position bytes of blobs, but there are $blobsEnd")
        return entries
    }
}

/**
 * Index files (`index/<id>`), each listing the blobs of the packs one backup wrote. Deflated as a
 * whole on disk ([encode] is the bytes before that, [decode] after).
 */
public object IndexFormat {
    internal const val VERSION: Int = 1
    private val MAGIC = "XBIX".encodeToByteArray()
    private const val FLAG_COMPRESSED = 1

    /** Where index files live: `index/ab12...`. */
    public fun fileName(id: Hash): String = "index/" + id.hex()

    /** For each pack, the blobs in it, before deflating. */
    public fun encode(packs: Map<Hash, List<PackEntry>>): ByteArray {
        val out = Codec.Out(64 + packs.values.sumOf { it.size } * 48)
        out.bytes(MAGIC).u8(VERSION).varint(packs.size)
        for ((pack, entries) in packs) {
            out.hash(pack).varint(entries.size)
            for (entry in entries) {
                out.u8(entry.type.ordinal).u8(if (entry.compressed) FLAG_COMPRESSED else 0).hash(entry.id)
                    .varint(entry.offset).varint(entry.storedLength).varint(entry.rawLength)
            }
        }
        return out.toArray()
    }

    /** What [encode] wrote, once inflated. */
    public fun decode(raw: ByteArray, name: String): Map<Hash, List<PackEntry>> {
        val input = Codec.In(raw, name)
        if (!input.bytes(4).contentEquals(MAGIC)) throw CorruptException("$name isn't an index file")
        val version = input.u8()
        if (version != VERSION) throw CorruptException("$name is index version $version; this xBackup reads $VERSION")
        val packCount = input.count(raw.size / 33)
        val packs = LinkedHashMap<Hash, List<PackEntry>>()
        repeat(packCount) {
            val pack = input.hash()
            val count = input.count(raw.size / 36)
            val entries = ArrayList<PackEntry>(count)
            repeat(count) {
                val type = BlobType.of(input.u8())
                val flags = input.u8()
                val id = input.hash()
                val offset = input.varint()
                val stored = input.count(PackFormat.MAX_BLOB)
                val rawLength = input.count(PackFormat.MAX_BLOB)
                entries += PackEntry(id, type, flags and FLAG_COMPRESSED != 0, offset, stored, rawLength)
            }
            packs[pack] = entries
        }
        input.expectEnd()
        return packs
    }

    /**
     * The most an index file of [stored] bytes may inflate to: hashes don't compress, so a real
     * index inflates to about twice its size, and a hostile remote's "index" that inflates far
     * past that is refused before it eats the memory.
     */
    public fun inflateLimit(stored: Int): Int = minOf(1L shl 30, maxOf(16L shl 20, stored.toLong() * 32)).toInt()
}

/**
 * One folder of a snapshot: its entries, sorted by name. Stored as a [BlobType.TREE] blob, so a
 * folder that didn't change between backups is the same blob and stored once.
 */
public class Tree(nodes: List<Node>) {
    public val nodes: List<Node>

    init {
        val sorted = nodes.sortedWith(NAMES)
        for (i in 1 until sorted.size) {
            require(sorted[i].name != sorted[i - 1].name) { "two entries named ${sorted[i].name}" }
        }
        this.nodes = sorted
    }

    /** The entry called [name], or `null`. */
    public operator fun get(name: String): Node? {
        var low = 0
        var high = nodes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = compareNames(nodes[mid].name, name)
            when {
                c == 0 -> return nodes[mid]
                c < 0 -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return null
    }

    /** What an entry is. */
    public enum class Kind {
        /** A file, stored as one or more data blobs. */
        FILE,

        /** A folder: [Node.ref] is its tree. */
        DIR,

        /** A region file: [Node.ref] is its [RegionLayout]. */
        REGION,

        /** A SQLite database, copied consistently (by SQLite, or by its plugin's writer); the blobs hold that copy. */
        SQLITE,

        /** A symbolic link: [Node.target]. */
        LINK,
    }

    /**
     * One entry.
     *
     * @param mtime last modified, in milliseconds since 1970
     * @param size the file's size on disk when it was read
     * @param mode POSIX permission bits, or -1 if the system has none
     * @param blobs the content, in order (files and databases)
     * @param contentSize the content's length: the file's size, or the database copy's
     * @param contentHash the SHA-256 of the whole content, checked after a restore
     * @param ref the tree (folders) or layout (region files)
     * @param target where a link points
     * @param walSize a database's write-ahead log size when read, or -1
     * @param walMtime a database's write-ahead log's last modified time, or -1
     */
    public data class Node(
        val name: String,
        val kind: Kind,
        val mtime: Long,
        val size: Long,
        val mode: Int,
        val blobs: List<Hash>,
        val contentSize: Long,
        val contentHash: Hash?,
        val ref: Hash?,
        val target: String?,
        val walSize: Long,
        val walMtime: Long,
    ) {
        init {
            require(!(name.isEmpty() || '/' in name || name == "." || name == ".." || '\u0000' in name)) { "not a file name: $name" }
            when (kind) {
                Kind.FILE, Kind.SQLITE -> requireNotNull(contentHash) { "content hash" }
                Kind.DIR, Kind.REGION -> requireNotNull(ref) { "ref" }
                Kind.LINK -> requireNotNull(target) { "target" }
            }
        }

        /** The same entry under another name. */
        public fun named(other: String): Node = copy(name = other)

        public companion object {
            public fun file(name: String, mtime: Long, size: Long, mode: Int, blobs: List<Hash>, contentHash: Hash): Node =
                Node(name, Kind.FILE, mtime, size, mode, blobs, size, contentHash, null, null, -1, -1)

            public fun database(name: String, mtime: Long, size: Long, mode: Int, blobs: List<Hash>, contentSize: Long, contentHash: Hash, walSize: Long, walMtime: Long): Node =
                Node(name, Kind.SQLITE, mtime, size, mode, blobs, contentSize, contentHash, null, null, walSize, walMtime)

            public fun dir(name: String, mtime: Long, mode: Int, tree: Hash): Node =
                Node(name, Kind.DIR, mtime, 0, mode, emptyList(), 0, null, tree, null, -1, -1)

            public fun region(name: String, mtime: Long, size: Long, mode: Int, layout: Hash): Node =
                Node(name, Kind.REGION, mtime, size, mode, emptyList(), size, null, layout, null, -1, -1)

            public fun link(name: String, mtime: Long, target: String): Node =
                Node(name, Kind.LINK, mtime, 0, -1, emptyList(), 0, null, null, target, -1, -1)
        }
    }

    public fun encode(): ByteArray {
        val out = Codec.Out(32 + nodes.size * 96)
        out.u8('T'.code).u8(VERSION).varint(nodes.size)
        for (node in nodes) {
            out.text(node.name).u8(node.kind.ordinal).i64(node.mtime).varint(node.size).u32(node.mode)
            when (node.kind) {
                Kind.FILE, Kind.SQLITE -> {
                    out.varint(node.contentSize).hash(node.contentHash!!).varint(node.blobs.size)
                    for (blob in node.blobs) out.hash(blob)
                    if (node.kind == Kind.SQLITE) out.i64(node.walSize).i64(node.walMtime)
                }
                Kind.DIR, Kind.REGION -> out.hash(node.ref!!)
                Kind.LINK -> out.text(node.target!!)
            }
        }
        return out.toArray()
    }

    public companion object {
        internal const val VERSION: Int = 1
        private const val MAX_NODES = 1 shl 22

        /** Java's `String.compareTo`, by UTF-16 code unit: the order 1.x sorted trees in. */
        public fun compareNames(x: String, y: String): Int = x.compareTo(y)

        // Before EMPTY, which sorts with it.
        private val NAMES = Comparator<Node> { x, y -> compareNames(x.name, y.name) }
        public val EMPTY: Tree = Tree(emptyList())

        public fun decode(bytes: ByteArray, what: String): Tree {
            val input = Codec.In(bytes, what)
            if (input.u8() != 'T'.code) throw CorruptException("$what isn't a tree")
            val version = input.u8()
            if (version != VERSION) throw CorruptException("$what is tree version $version; this xBackup reads $VERSION")
            val count = input.count(MAX_NODES)
            val kinds = Kind.entries
            val nodes = ArrayList<Node>(minOf(count, 4096))
            repeat(count) {
                val name = input.text()
                val k = input.u8()
                if (k >= kinds.size) throw CorruptException("$what has an entry of unknown kind $k")
                val kind = kinds[k]
                val mtime = input.i64()
                val size = input.varint()
                val mode = input.u32()
                try {
                    nodes += when (kind) {
                        Kind.FILE, Kind.SQLITE -> {
                            val contentSize = input.varint()
                            val contentHash = input.hash()
                            val blobCount = input.count(1 shl 24)
                            val blobs = ArrayList<Hash>(minOf(blobCount, 4096))
                            repeat(blobCount) { blobs += input.hash() }
                            var walSize = -1L
                            var walMtime = -1L
                            if (kind == Kind.SQLITE) {
                                walSize = input.i64()
                                walMtime = input.i64()
                            }
                            Node(name, kind, mtime, size, mode, blobs, contentSize, contentHash, null, null, walSize, walMtime)
                        }
                        Kind.DIR, Kind.REGION -> Node(name, kind, mtime, size, mode, emptyList(), size, null, input.hash(), null, -1, -1)
                        Kind.LINK -> Node(name, kind, mtime, size, mode, emptyList(), 0, null, null, input.text(), -1, -1)
                    }
                } catch (e: IllegalArgumentException) {
                    throw CorruptException("$what: ${e.message}", e)
                }
            }
            input.expectEnd()
            try {
                return Tree(nodes)
            } catch (e: IllegalArgumentException) {
                throw CorruptException("$what: ${e.message}", e)
            }
        }
    }
}

/**
 * A region file (`.mca`) as a snapshot keeps it: the file's length, and for each of its 1,024
 * chunks where it sits, its timestamp, and the blob holding its record (the 4-byte length,
 * compression byte and payload, exactly as on disk).
 *
 * Restoring writes the header and each record back at the same place, so the file Minecraft
 * reads is the one that was backed up. Unused sectors come back as zeros.
 */
public class RegionLayout(
    public val fileLength: Long,
    private val location: IntArray,
    private val timestamp: IntArray,
    private val recordLength: IntArray,
    private val blob: Array<Hash?>,
) {
    init {
        require(location.size == CHUNKS && timestamp.size == CHUNKS && recordLength.size == CHUNKS && blob.size == CHUNKS) { "a region has 1024 chunks" }
        for (i in 0 until CHUNKS) {
            require(present(i) == (blob[i] != null)) { "chunk $i has a location but no blob, or a blob but no location" }
        }
    }

    /** The raw location entry: sector offset in the top 24 bits, sector count in the low 8. 0 for none. */
    public fun location(chunk: Int): Int = location[chunk]

    public fun timestamp(chunk: Int): Int = timestamp[chunk]

    public fun recordLength(chunk: Int): Int = recordLength[chunk]

    /** The chunk's blob, or `null` if the region has no such chunk. */
    public fun blob(chunk: Int): Hash? = blob[chunk]

    public fun present(chunk: Int): Boolean = location[chunk] != 0

    public val chunkCount: Int get() = location.count { it != 0 }

    /** The 8 KiB header as Minecraft writes it: locations, then timestamps, big-endian. */
    public fun header(): ByteArray {
        val out = Codec.Out(HEADER)
        for (i in 0 until CHUNKS) out.u32(location[i])
        for (i in 0 until CHUNKS) out.u32(timestamp[i])
        return out.toArray()
    }

    public fun encode(): ByteArray {
        val present = chunkCount
        val out = Codec.Out(32 + present * 48)
        out.u8('R'.code).u8(VERSION).varint(fileLength).varint(present)
        for (i in 0 until CHUNKS) {
            if (present(i)) out.varint(i).u32(location[i]).u32(timestamp[i]).varint(recordLength[i]).hash(blob[i]!!)
        }
        // Chunks with no data but a timestamp, so the header comes back exactly.
        var stamped = 0
        for (i in 0 until CHUNKS) if (!present(i) && timestamp[i] != 0) stamped++
        out.varint(stamped)
        for (i in 0 until CHUNKS) if (!present(i) && timestamp[i] != 0) out.varint(i).u32(timestamp[i])
        return out.toArray()
    }

    /** A key for a loose pass's layout in memory, before it's stored: the same for the same layout. */
    public fun overlayKey(): ByteArray {
        val out = Codec.Out(8 + CHUNKS * (12 + Hash.BYTES))
        out.i64(fileLength)
        for (i in 0 until CHUNKS) {
            out.u32(location[i]).u32(timestamp[i]).u32(recordLength[i])
            val b = blob[i]
            if (b == null) out.bytes(ByteArray(Hash.BYTES)) else out.hash(b)
        }
        return out.toArray()
    }

    override fun equals(other: Any?): Boolean = other is RegionLayout && fileLength == other.fileLength &&
        location.contentEquals(other.location) && timestamp.contentEquals(other.timestamp) &&
        recordLength.contentEquals(other.recordLength) && blob.contentEquals(other.blob)

    override fun hashCode(): Int = location.contentHashCode() * 31 + fileLength.hashCode()

    public companion object {
        public const val CHUNKS: Int = 1024
        public const val SECTOR: Int = 4096
        public const val HEADER: Int = 2 * SECTOR
        internal const val VERSION: Int = 1

        public fun sectorOffset(location: Int): Long = ((location ushr 8) and 0xFFFFFF).toLong()

        public fun sectorCount(location: Int): Int = location and 0xFF

        public fun decode(bytes: ByteArray, what: String): RegionLayout {
            val input = Codec.In(bytes, what)
            if (input.u8() != 'R'.code) throw CorruptException("$what isn't a region layout")
            val version = input.u8()
            if (version != VERSION) throw CorruptException("$what is region layout version $version; this xBackup reads $VERSION")
            val fileLength = input.varint()
            val location = IntArray(CHUNKS)
            val timestamp = IntArray(CHUNKS)
            val recordLength = IntArray(CHUNKS)
            val blob = arrayOfNulls<Hash>(CHUNKS)
            val present = input.count(CHUNKS)
            var last = -1
            repeat(present) {
                val i = input.count(CHUNKS - 1)
                if (i <= last) throw CorruptException("$what lists its chunks out of order")
                last = i
                location[i] = input.u32()
                timestamp[i] = input.u32()
                recordLength[i] = input.count(PackFormat.MAX_BLOB)
                blob[i] = input.hash()
                if (location[i] == 0) throw CorruptException("$what lists chunk $i with no location")
            }
            val stamped = input.count(CHUNKS)
            repeat(stamped) {
                val i = input.count(CHUNKS - 1)
                if (location[i] != 0) throw CorruptException("$what lists chunk $i twice")
                timestamp[i] = input.u32()
            }
            input.expectEnd()
            return RegionLayout(fileLength, location, timestamp, recordLength, blob)
        }

        /**
         * Why a region header ([location]s) can't be trusted for a file of [fileLength], or `null`
         * if every chunk's sectors are inside the file, after the header, and don't overlap. A
         * file that fails this is backed up whole.
         */
        public fun problem(location: IntArray, fileLength: Long): String? {
            val ranges = LongArray(CHUNKS)
            var count = 0
            for (i in 0 until CHUNKS) {
                val l = location[i]
                if (l == 0) continue
                val offset = sectorOffset(l)
                val sectors = sectorCount(l)
                if (offset < 2 || sectors == 0) return "chunk $i points into the header"
                if (offset * SECTOR + 5 > fileLength) return "chunk $i points past the end of the file"
                ranges[count++] = (offset shl 8) or sectors.toLong()
            }
            ranges.sort(0, count)
            var end = 0L
            for (i in 0 until count) {
                val offset = ranges[i] ushr 8
                if (offset < end) return "two chunks share sectors"
                end = offset + (ranges[i] and 0xFF)
            }
            return null
        }
    }
}
