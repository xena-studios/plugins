package co.xenastudios.xbackup.store

/** The repository's binary encoding: big-endian numbers, unsigned varints, length-prefixed text. */
public object Codec {

    /** A growable byte buffer to encode into. */
    public class Out(capacity: Int = 256) {
        private var bytes = ByteArray(maxOf(16, capacity))

        /** How many bytes it holds. */
        public var size: Int = 0
            private set

        private fun room(more: Int) {
            if (size + more > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + more))
        }

        public fun u8(value: Int): Out {
            room(1)
            bytes[size++] = value.toByte()
            return this
        }

        public fun u32(value: Int): Out {
            room(4)
            bytes[size++] = (value ushr 24).toByte()
            bytes[size++] = (value ushr 16).toByte()
            bytes[size++] = (value ushr 8).toByte()
            bytes[size++] = value.toByte()
            return this
        }

        public fun i64(value: Long): Out {
            u32((value ushr 32).toInt())
            return u32(value.toInt())
        }

        /** An unsigned varint: 7 bits a byte, low bits first. */
        public fun varint(value: Long): Out {
            require(value >= 0) { "varints are unsigned: $value" }
            room(10)
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                bytes[size++] = ((v and 0x7F) or 0x80).toByte()
                v = v ushr 7
            }
            bytes[size++] = v.toByte()
            return this
        }

        public fun varint(value: Int): Out = varint(value.toLong())

        public fun hash(hash: Hash): Out {
            room(Hash.BYTES)
            Hash.putLong(bytes, size, hash.a)
            Hash.putLong(bytes, size + 8, hash.b)
            Hash.putLong(bytes, size + 16, hash.c)
            Hash.putLong(bytes, size + 24, hash.d)
            size += Hash.BYTES
            return this
        }

        public fun bytes(data: ByteArray, offset: Int = 0, length: Int = data.size): Out {
            room(length)
            data.copyInto(bytes, size, offset, offset + length)
            size += length
            return this
        }

        public fun text(text: String): Out {
            val utf8 = text.encodeToByteArray()
            varint(utf8.size)
            return bytes(utf8)
        }

        public fun toArray(): ByteArray = bytes.copyOf(size)
    }

    /** Reads what [Out] wrote. Running off the end is a [CorruptException]. */
    public class In(private val bytes: ByteArray, offset: Int, length: Int, private val what: String) {
        public constructor(bytes: ByteArray, what: String) : this(bytes, 0, bytes.size, what)

        private var pos = offset
        private val end = offset + length

        private fun need(n: Int) {
            if (n < 0 || end - pos < n) throw CorruptException("$what ends early")
        }

        public fun u8(): Int {
            need(1)
            return bytes[pos++].toInt() and 0xFF
        }

        public fun u32(): Int {
            need(4)
            val value = (bytes[pos].toInt() and 0xFF shl 24) or (bytes[pos + 1].toInt() and 0xFF shl 16) or
                (bytes[pos + 2].toInt() and 0xFF shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            return value
        }

        public fun i64(): Long = (u32().toLong() shl 32) or (u32().toLong() and 0xFFFFFFFFL)

        public fun varint(): Long {
            var value = 0L
            var shift = 0
            while (shift < 64) {
                val b = u8()
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw CorruptException("$what has a number that never ends")
        }

        /** A varint that must fit an int from 0 to [max]. */
        public fun count(max: Int): Int {
            val value = varint()
            if (value < 0 || value > max) throw CorruptException("$what has a count of $value, more than $max")
            return value.toInt()
        }

        public fun hash(): Hash {
            need(Hash.BYTES)
            val hash = Hash.read(bytes, pos)
            pos += Hash.BYTES
            return hash
        }

        public fun bytes(length: Int): ByteArray {
            need(length)
            val out = bytes.copyOfRange(pos, pos + length)
            pos += length
            return out
        }

        public fun text(): String {
            val length = count(1 shl 20)
            need(length)
            val text = bytes.decodeToString(pos, pos + length)
            pos += length
            return text
        }

        public val atEnd: Boolean get() = pos == end

        public fun expectEnd() {
            if (pos != end) throw CorruptException("$what has ${end - pos} bytes left over")
        }
    }
}
