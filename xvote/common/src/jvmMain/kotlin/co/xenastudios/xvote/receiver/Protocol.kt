package co.xenastudios.xvote.receiver

import co.xenastudios.xvote.Json
import co.xenastudios.xvote.api.VoteSource
import co.xenastudios.xvote.vote.IncomingVote
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Votifier's two protocols, byte for byte as NuVotifier speaks them, so every server list that works
 * with NuVotifier works with xVote (decisions/2026-11-18-xvote.md). The server greets each
 * connection with `VOTIFIER 2 <challenge>\n`. The list then sends either:
 *
 * - **v1:** one 256-byte block, RSA-encrypted (PKCS#1 v1.5) with the server's 2048-bit public key,
 *   holding `VOTE\n<service>\n<username>\n<address>\n<timestamp>\n` in ASCII. No reply.
 * - **v2:** the two bytes `0x733A`, a two-byte length, then that many bytes of JSON:
 *   `{"payload": "<json>", "signature": "<base64>"}`. The payload holds `serviceName`, `username`,
 *   `address`, `timestamp` and the greeting's `challenge`; the signature is its HMAC-SHA256 with the
 *   list's token. The reply is `{"status":"ok"}` or an error, then `\r\n`.
 */
public object Protocol {
    /** The first two bytes of a v2 vote. */
    public const val V2_MAGIC: Int = 0x733A

    /** A v1 vote's size: one block of a 2048-bit key. */
    public const val V1_BLOCK: Int = 256

    /** The largest v2 message read. NuVotifier refuses more than 1,024 bytes; a little room costs nothing. */
    public const val V2_MAX: Int = 2048

    /** Longest name a v2 vote may carry, as NuVotifier checks. */
    internal const val MAX_USERNAME = 16

    private val RANDOM = SecureRandom()

    /** A new challenge or token, as NuVotifier makes them: 130 random bits in base 32. */
    @JvmStatic
    public fun newToken(): String = BigInteger(130, RANDOM).toString(32)

    /** What the server says first. */
    public fun greeting(challenge: String): ByteArray = "VOTIFIER 2 $challenge\n".toByteArray(Charsets.UTF_8)

    /** The reply to a v2 vote that was accepted. */
    public fun ok(): ByteArray = "{\"status\":\"ok\"}\r\n".toByteArray(Charsets.UTF_8)

    /** The reply to a v2 vote that wasn't. */
    public fun error(cause: String, message: String): ByteArray =
        (Json.write(linkedMapOf("status" to "error", "cause" to cause, "error" to message)) + "\r\n").toByteArray(Charsets.UTF_8)

    /** Reads a v1 block. */
    internal fun decodeV1(block: ByteArray, key: PrivateKey, now: Long): IncomingVote {
        if (block.size != V1_BLOCK) throw ProtocolException.noise("QuietException", "A v1 vote is $V1_BLOCK bytes; got ${block.size}.", "v1-size")
        val plain = try {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            cipher.doFinal(block)
        } catch (e: GeneralSecurityException) {
            val why = "Couldn't decrypt a v1 vote. Check the list has this server's public key (/xvote key)."
            // One key for every sender: v1 can't tell a list from a port scanner, so scanners mustn't
            // use up the warnings a list's own mistakes get.
            throw ProtocolException.vote("QuietException", why, "v1-decrypt", "$why (Said once until the next reload; a port scanner can cause it too.)")
        }
        val fields = String(plain, Charsets.US_ASCII).split("\n")
        if (fields.size < 5) throw ProtocolException.noise("QuietException", "A v1 vote had ${fields.size} fields instead of 5; the list sent it wrong.", "v1-fields")
        if (fields[0] != "VOTE") throw ProtocolException.noise("QuietException", "A v1 vote didn't start with VOTE; the list sent it wrong.", "v1-opcode")
        return IncomingVote(fields[1], fields[2], fields[3], fields[4], VoteSource.VOTIFIER_V1, now)
    }

    /** Reads and checks a v2 message. [tokenFor] is a service name's token, or `null` if it has none (then `default`'s is tried). */
    internal fun decodeV2(message: String, challenge: String, tokenFor: (String) -> String?, now: Long): IncomingVote {
        try {
            val outer = Json.parse(message) as? Map<*, *> ?: throw Json.Malformed("not an object")
            val payload = string(outer, "payload")
            val vote = Json.parse(payload) as? Map<*, *> ?: throw Json.Malformed("the payload isn't an object")
            if (challenge != string(vote, "challenge")) throw ProtocolException.noise("CorruptedFrameException", "Challenge is not valid", "v2-challenge")
            val service = string(vote, "serviceName")
            // As it may be logged: no line breaks or other control characters a sender could fake log lines with.
            val shown = IncomingVote.clean(service)
            var token = tokenFor(service)
            // A service with a token of its own is one the owner added, so its mistakes are logged by
            // name; any other name is the sender's choice, so they share one warning.
            val own = token != null
            if (token == null) token = tokenFor("default")
            if (token == null) {
                throw ProtocolException.vote(
                    "RuntimeException", "Unknown service '$shown'", "v2-service",
                    "A v2 vote came from \"$shown\", which has no token and there's no default one. Add one with /xvote token. (Said once until the next reload.)",
                )
            }
            val signature = try {
                Base64.getDecoder().decode(string(outer, "signature"))
            } catch (e: IllegalArgumentException) {
                throw badSignature(shown, own)
            }
            if (!MessageDigest.isEqual(hmac(token, payload), signature)) throw badSignature(shown, own)
            vote["uuid"]?.let { java.util.UUID.fromString(it.toString()) }
            val username = string(vote, "username")
            if (username.length > MAX_USERNAME) throw ProtocolException.noise("CorruptedFrameException", "Username too long", "v2-username")
            return IncomingVote(service, username, string(vote, "address"), timestamp(vote["timestamp"]), VoteSource.VOTIFIER_V2, now)
        } catch (e: ProtocolException) {
            throw e
        } catch (e: Exception) {
            // Missing fields, a field of the wrong kind, a malformed uuid or not JSON at all.
            throw ProtocolException.noise(e.javaClass.simpleName, "Not a well-formed vote: " + IncomingVote.clean(e.message), "v2-malformed")
        }
    }

    private fun badSignature(service: String, own: Boolean): ProtocolException = ProtocolException.vote(
        "CorruptedFrameException", "Signature is not valid (invalid token?)", if (own) "v2-signature:$service" else "v2-signature",
        "A v2 vote from \"$service\" was signed with a different token. Give the list its token again (/xvote token)." +
            if (own) " (Further errors from this list are quiet until the next reload.)" else " (Said once until the next reload; a port scanner can cause it too.)",
    )

    /** The HMAC-SHA256 of the payload's UTF-8 bytes, keyed with the token's UTF-8 bytes. */
    @JvmStatic
    public fun hmac(token: String, payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    private fun string(map: Map<*, *>, name: String): String = when (val value = map[name]) {
        null -> throw IllegalStateException("no $name")
        is String -> value
        is Json.Number -> value.text
        is Map<*, *>, is List<*> -> throw IllegalStateException("$name isn't text")
        else -> value.toString()
    }

    // Lists send a number or a string; NuVotifier keeps either as text.
    private fun timestamp(value: Any?): String = when (value) {
        null -> throw IllegalStateException("no timestamp")
        is Json.Number -> value.toLongOrNull()?.toString() ?: value.text
        is String -> value
        is Map<*, *>, is List<*> -> throw IllegalStateException("timestamp isn't text")
        else -> value.toString()
    }
}

/**
 * A vote that couldn't be read or wasn't genuine. [cause] is the error's short name a Votifier v2
 * reply carries, as NuVotifier names it, so a list shows owners the same error either way. A **vote
 * error** is what a list set up wrong causes (a wrong token, the wrong public key): logged once per
 * key, from a budget of its own. **Noise** is what anything else on the internet sends: logged once
 * per kind, without the address or names it carried.
 */
internal class ProtocolException private constructor(
    val causeName: String,
    message: String,
    val logKey: String,
    val voteError: Boolean,
    val logLine: String,
) : Exception(message, null, false, false) {
    companion object {
        fun vote(cause: String, message: String, key: String, logLine: String) = ProtocolException(cause, message, key, true, logLine)

        fun noise(cause: String, message: String, kind: String) = ProtocolException(cause, message, kind, false, message)
    }
}
