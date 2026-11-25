package co.xenastudios.core.common.proxy

import co.xenastudios.core.common.Outcome
import kotlin.time.Instant

/**
 * Signs a frame with the network's secret: HMAC-SHA256 on the JVM (core-jvm's `HmacSigner`), with
 * a key derived from the secret for this use alone. [sign] returns at least [ProxyFrame.TAG]
 * bytes, of which a frame keeps the first [ProxyFrame.TAG].
 */
public fun interface FrameSigner {
    public fun sign(data: ByteArray): ByteArray
}

/**
 * One message on the wire, as a [ProxyChannel] sends it in a plugin message (version 1):
 *
 * | Bytes | |
 * |---|---|
 * | 1 | `X`, so a stray message on the channel is told apart at once |
 * | 1 | the version, 1 |
 * | 1 | flags: 1 when the proxy sent it |
 * | 1 + n | the topic's length, then its ASCII |
 * | 8 | when it was sent, in epoch milliseconds |
 * | 8 | a random id, so a message heard twice is acted on once |
 * | 2 + n | the payload's length, then its bytes |
 * | 16 | the first 16 bytes of HMAC-SHA256, by the network's secret, over the channel's name and everything above |
 *
 * The channel's name is signed but not sent, so a message for one plugin can't be passed off on
 * another's channel, and the direction is signed, so one a server sent can't come back to a
 * server as if the proxy sent it.
 */
public class ProxyFrame(
    /** Whether the proxy sent it. */
    public val fromProxy: Boolean,
    public val topic: String,
    public val sentAt: Instant,
    public val id: Long,
    public val payload: ByteArray,
) {
    /** Why a frame was refused, for the console: each is a reason a server would ignore a message. */
    public enum class Problem(public val reason: String) {
        MALFORMED("it isn't a message of ours, or it was cut short"),
        VERSION("it's from a newer version of the Xena plugins; update this one"),
        SIGNATURE("it isn't signed with this server's secret: the proxy and every server behind it need the same one"),
        DIRECTION("it claims to come from the wrong side of the network"),
        STALE("it was sent more than five minutes ago or ahead, by the sender's clock: check the machines' clocks"),
        REPEATED("it was already received"),
    }

    public companion object {
        /** The frame's first byte. */
        public const val MAGIC: Byte = 'X'.code.toByte()

        /** The layout this core writes and reads. */
        public const val VERSION: Byte = 1

        /** The bytes of the signature a frame keeps: HMAC-SHA256 cut to 128 bits, as RFC 2104 allows. */
        public const val TAG: Int = 16

        private const val FROM_PROXY = 1
        private val TOPIC = Regex("[a-z0-9._-]{1,64}")

        /** Whether [topic] can be a topic: 1 to 64 of `a-z`, `0-9`, `.`, `_` and `-`. */
        public fun isTopic(topic: String): Boolean = TOPIC.matches(topic)

        /** The frame's bytes, signed by [signer] for [channel]. Throws on a topic or payload a channel refuses first. */
        public fun encode(channel: String, frame: ProxyFrame, signer: FrameSigner): ByteArray {
            require(isTopic(frame.topic)) { "\"${frame.topic}\" isn't a topic" }
            require(frame.payload.size <= ProxyChannel.MAX_PAYLOAD) { "a payload is at most ${ProxyChannel.MAX_PAYLOAD} bytes" }
            val topic = frame.topic.encodeToByteArray()
            val body = ByteArray(3 + 1 + topic.size + 8 + 8 + 2 + frame.payload.size)
            var at = 0
            body[at++] = MAGIC
            body[at++] = VERSION
            body[at++] = (if (frame.fromProxy) FROM_PROXY else 0).toByte()
            body[at++] = topic.size.toByte()
            topic.copyInto(body, at)
            at += topic.size
            at = putLong(body, at, frame.sentAt.toEpochMilliseconds())
            at = putLong(body, at, frame.id)
            body[at++] = (frame.payload.size shr 8).toByte()
            body[at++] = frame.payload.size.toByte()
            frame.payload.copyInto(body, at)
            val tag = signer.sign(signed(channel, body))
            check(tag.size >= TAG) { "a signature is at least $TAG bytes" }
            return body + tag.copyOf(TAG)
        }

        /** The frame in [bytes], if it's well formed and signed by [signer] for [channel]. Doesn't check time, direction or repeats. */
        public fun decode(channel: String, bytes: ByteArray, signer: FrameSigner): Outcome<ProxyFrame, Problem> {
            if (bytes.size < 4 + 8 + 8 + 2 + TAG || bytes[0] != MAGIC) return Outcome.Err(Problem.MALFORMED)
            if (bytes[1] != VERSION) return Outcome.Err(if (bytes[1] > VERSION) Problem.VERSION else Problem.MALFORMED)
            val flags = bytes[2].toInt()
            var at = 3
            val topicLength = bytes[at++].toInt() and 0xFF
            val payloadAt = at + topicLength + 8 + 8
            if (topicLength == 0 || payloadAt + 2 + TAG > bytes.size) return Outcome.Err(Problem.MALFORMED)
            val topic = bytes.decodeToString(at, at + topicLength)
            if (!isTopic(topic)) return Outcome.Err(Problem.MALFORMED)
            at += topicLength
            val sentAt = getLong(bytes, at)
            at += 8
            val id = getLong(bytes, at)
            at += 8
            val length = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
            at += 2
            if (at + length + TAG != bytes.size) return Outcome.Err(Problem.MALFORMED)
            val body = bytes.copyOfRange(0, at + length)
            val expected = signer.sign(signed(channel, body))
            if (expected.size < TAG || !sameTag(expected, bytes, at + length)) return Outcome.Err(Problem.SIGNATURE)
            return Outcome.Ok(ProxyFrame(flags and FROM_PROXY != 0, topic, Instant.fromEpochMilliseconds(sentAt), id, bytes.copyOfRange(at, at + length)))
        }

        // The channel's name, its length first so no two channel and body pairs sign the same, then the body.
        private fun signed(channel: String, body: ByteArray): ByteArray {
            val name = channel.encodeToByteArray()
            val out = ByteArray(1 + name.size + body.size)
            out[0] = name.size.toByte()
            name.copyInto(out, 1)
            body.copyInto(out, 1 + name.size)
            return out
        }

        // Every byte compared whatever the first difference, so the time taken says nothing about the tag.
        private fun sameTag(expected: ByteArray, bytes: ByteArray, from: Int): Boolean {
            var difference = 0
            for (i in 0 until TAG) difference = difference or (expected[i].toInt() xor bytes[from + i].toInt())
            return difference == 0
        }

        private fun putLong(out: ByteArray, at: Int, value: Long): Int {
            for (i in 0 until 8) out[at + i] = (value shr (56 - 8 * i)).toByte()
            return at + 8
        }

        private fun getLong(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            return value
        }
    }
}
