package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import kotlin.uuid.Uuid

/**
 * The uuid 1.x gave an account made by name through Vault (a town's bank): Java's
 * `UUID.nameUUIDFromBytes("xMoney account:" + name.toLowerCase())`, a version 3 uuid of the
 * name's MD5, worked out here by hand (common code has no `MessageDigest`) so 2.0 finds the
 * same accounts 1.x made.
 */
public object NameUuid {
    public fun of(name: String): PlayerRef {
        val hash = md5(("xMoney account:" + name.lowercase()).encodeToByteArray())
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x30).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        return PlayerRef.of(Uuid.fromByteArray(hash))
    }

    /** The uuid's version: 4 for online-mode players, 3 for offline, 2 for Citizens' NPCs. */
    public fun version(id: PlayerRef): Int = id.toString()[14].digitToIntOrNull(16) ?: 0

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    private val K = IntArray(64) { i -> (kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0).toLong().toInt() }

    public fun md5(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val m = IntArray(16)
        for (chunk in 0 until padded.size / 64) {
            for (i in 0 until 16) {
                val o = chunk * 64 + i * 4
                m[i] = (padded[o].toInt() and 0xff) or ((padded[o + 1].toInt() and 0xff) shl 8) or
                    ((padded[o + 2].toInt() and 0xff) shl 16) or ((padded[o + 3].toInt() and 0xff) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                var f: Int
                val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                f += a + K[i] + m[g]
                a = d
                d = c
                c = b
                b += (f shl S[i]) or (f ushr (32 - S[i]))
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }
        val out = ByteArray(16)
        for ((n, word) in intArrayOf(a0, b0, c0, d0).withIndex()) for (i in 0 until 4) out[n * 4 + i] = (word ushr (8 * i)).toByte()
        return out
    }
}
