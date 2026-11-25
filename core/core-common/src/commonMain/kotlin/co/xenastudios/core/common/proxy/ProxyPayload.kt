package co.xenastudios.core.common.proxy

import co.xenastudios.core.api.PlayerRef
import kotlin.uuid.Uuid

/**
 * Writes and reads a [ProxyChannel] message's payload, so plugins don't each make up a format:
 * numbers big-endian, text as UTF-8 after its length, players by UUID. A plugin that changes what
 * it sends under a topic puts a version first, or uses a new topic, since a network's servers
 * aren't all updated at once.
 *
 * ```kotlin
 * val payload = ProxyPayload.write { int(1); player(sender); text(line) }
 * ProxyPayload.read(message.payload) { val version = int(); val from = player(); val line = text() }
 * ```
 */
public object ProxyPayload {
    /** The bytes [block] writes. */
    public fun write(block: Writer.() -> Unit): ByteArray = Writer().apply(block).toByteArray()

    /** What [block] reads from [payload], or `null` if the payload ends early or has bytes left over: a message from another version. */
    public fun <T> read(payload: ByteArray, block: Reader.() -> T): T? {
        val reader = Reader(payload)
        return try {
            reader.block().takeIf { reader.done }
        } catch (e: MalformedPayloadException) {
            null
        }
    }

    /** Thrown inside [read] when the payload isn't what's being read; [read] returns `null` for it. */
    public class MalformedPayloadException internal constructor(message: String) : RuntimeException(message)

    /** Builds a payload. */
    public class Writer internal constructor() {
        private var bytes = ByteArray(64)
        private var size = 0

        public fun byte(value: Int) {
            room(1)
            bytes[size++] = value.toByte()
        }

        public fun boolean(value: Boolean) {
            byte(if (value) 1 else 0)
        }

        public fun int(value: Int) {
            room(4)
            for (i in 0 until 4) bytes[size++] = (value shr (24 - 8 * i)).toByte()
        }

        public fun long(value: Long) {
            room(8)
            for (i in 0 until 8) bytes[size++] = (value shr (56 - 8 * i)).toByte()
        }

        /** Text, up to 65,535 bytes of UTF-8. */
        public fun text(value: String) {
            val encoded = value.encodeToByteArray()
            require(encoded.size <= 0xFFFF) { "text in a payload is at most 65,535 bytes" }
            room(2 + encoded.size)
            bytes[size++] = (encoded.size shr 8).toByte()
            bytes[size++] = encoded.size.toByte()
            encoded.copyInto(bytes, size)
            size += encoded.size
        }

        public fun uuid(value: Uuid) {
            value.toLongs { most, least ->
                long(most)
                long(least)
            }
        }

        public fun player(value: PlayerRef) {
            uuid(value.uuid)
        }

        internal fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun room(more: Int) {
            if (size + more > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + more))
        }
    }

    /** Reads a payload, in the order it was written. */
    public class Reader internal constructor(private val bytes: ByteArray) {
        private var at = 0

        internal val done: Boolean get() = at == bytes.size

        public fun byte(): Int {
            need(1)
            return bytes[at++].toInt()
        }

        public fun boolean(): Boolean = byte() != 0

        public fun int(): Int {
            need(4)
            var value = 0
            for (i in 0 until 4) value = (value shl 8) or (bytes[at++].toInt() and 0xFF)
            return value
        }

        public fun long(): Long {
            need(8)
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[at++].toLong() and 0xFF)
            return value
        }

        public fun text(): String {
            need(2)
            val length = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
            at += 2
            need(length)
            val value = bytes.decodeToString(at, at + length, throwOnInvalidSequence = false)
            at += length
            return value
        }

        public fun uuid(): Uuid = Uuid.fromLongs(long(), long())

        public fun player(): PlayerRef = PlayerRef.of(uuid())

        private fun need(count: Int) {
            if (at + count > bytes.size) throw MalformedPayloadException("the payload ends early")
        }
    }
}
