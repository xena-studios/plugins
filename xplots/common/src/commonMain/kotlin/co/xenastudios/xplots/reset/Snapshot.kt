package co.xenastudios.xplots.reset

/** Deflate and inflate, which the stdlib doesn't have: `java.util.zip` on the JVM (`JvmDeflate`). */
public interface Deflate {
    /** [bytes] deflated with a zlib header, as `DeflaterOutputStream` writes them. */
    public fun deflate(bytes: ByteArray): ByteArray

    /** [bytes] inflated, at most [max] bytes of them; throws [IllegalArgumentException] if they aren't deflated data or are longer. */
    public fun inflate(bytes: ByteArray, max: Int): ByteArray
}

/**
 * A plot's blocks, as 1.x stored them, byte for byte: a version byte (1), the width, height and
 * depth as 4-byte numbers, the palette's size, each block state as Java's `writeUTF` writes text
 * (a 2-byte length, then modified UTF-8), then one palette index per block (y, then z, then x),
 * sizes and indices as varints; all of it deflated. A 5×5 plot ten blocks high is a few hundred bytes.
 *
 * @param palette block states as Minecraft writes them, such as `minecraft:oak_stairs[facing=north]`
 */
public class Snapshot(public val width: Int, public val height: Int, public val depth: Int, public val palette: List<String>, public val indices: IntArray) {

    public fun encode(deflate: Deflate): ByteArray {
        val out = Bytes(256)
        out.byte(VERSION)
        out.int(width)
        out.int(height)
        out.int(depth)
        out.varInt(palette.size)
        for (state in palette) out.utf(state)
        for (index in indices) out.varInt(index)
        return deflate.deflate(out.toByteArray())
    }

    public companion object {
        private const val VERSION = 1
        private const val MAX_VOLUME = 16_000_000

        /** Reads stored bytes; throws [IllegalArgumentException] saying why if they aren't a snapshot this xPlots reads. */
        public fun decode(data: ByteArray, deflate: Deflate): Snapshot {
            val raw = deflate.inflate(data, 5 * MAX_VOLUME + 65_536 * 64)
            val input = Reader(raw)
            val version = input.byte()
            require(version == VERSION) { "snapshot version $version is newer than this xPlots" }
            val width = input.int()
            val height = input.int()
            val depth = input.int()
            val volume = width.toLong() * height * depth
            require(width >= 1 && height >= 1 && depth >= 1 && volume <= MAX_VOLUME) { "snapshot size $width×$height×$depth is impossible" }
            val size = input.varInt()
            require(size in 0..65_536) { "snapshot palette of $size is impossible" }
            val palette = ArrayList<String>(size)
            repeat(size) { palette += input.utf() }
            val indices = IntArray(volume.toInt())
            for (i in indices.indices) {
                val index = input.varInt()
                require(index in 0 until size) { "snapshot points past its palette" }
                indices[i] = index
            }
            return Snapshot(width, height, depth, palette, indices)
        }
    }

    /** A growing byte array, big-endian as `DataOutputStream` writes. */
    private class Bytes(capacity: Int) {
        private var buffer = ByteArray(capacity)
        private var size = 0

        fun byte(value: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = value.toByte()
        }

        fun int(value: Int) {
            byte(value ushr 24)
            byte(value ushr 16)
            byte(value ushr 8)
            byte(value)
        }

        fun varInt(value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                byte((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            byte(v)
        }

        /** Java's modified UTF-8, after its length in two bytes. */
        fun utf(text: String) {
            var length = 0
            for (c in text) length += when (c.code) {
                in 1..0x7F -> 1
                in 0..0x7FF -> 2
                else -> 3
            }
            require(length <= 65_535) { "block state too long to store: ${text.take(40)}..." }
            byte(length ushr 8)
            byte(length)
            for (c in text) {
                val code = c.code
                when (code) {
                    in 1..0x7F -> byte(code)
                    in 0..0x7FF -> {
                        byte(0xC0 or (code shr 6 and 0x1F))
                        byte(0x80 or (code and 0x3F))
                    }
                    else -> {
                        byte(0xE0 or (code shr 12 and 0x0F))
                        byte(0x80 or (code shr 6 and 0x3F))
                        byte(0x80 or (code and 0x3F))
                    }
                }
            }
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        fun byte(): Int {
            require(at < bytes.size) { "snapshot ends too soon" }
            return bytes[at++].toInt() and 0xFF
        }

        fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()

        fun varInt(): Int {
            var value = 0
            var shift = 0
            while (shift < 35) {
                val b = byte()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("snapshot number too long")
        }

        fun utf(): String {
            val length = (byte() shl 8) or byte()
            val end = at + length
            require(end <= bytes.size) { "snapshot ends too soon" }
            val out = StringBuilder(length)
            while (at < end) {
                val a = byte()
                when {
                    a and 0x80 == 0 -> out.append(a.toChar())
                    a and 0xE0 == 0xC0 -> out.append((((a and 0x1F) shl 6) or (byte() and 0x3F)).toChar())
                    a and 0xF0 == 0xE0 -> out.append((((a and 0x0F) shl 12) or ((byte() and 0x3F) shl 6) or (byte() and 0x3F)).toChar())
                    else -> throw IllegalArgumentException("snapshot text is malformed")
                }
            }
            return out.toString()
        }
    }
}
