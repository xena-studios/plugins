package co.xenastudios.xbans.ip

/**
 * Turns an IP address into the key xBans stores: the address as Java writes it (1.x's
 * `InetAddress.getHostAddress`), or, with hashing on, a salted hash of it (HMAC-SHA256, the first
 * 16 bytes in hex, exactly as 1.x made them, so a 1.x database's keys still match). The same
 * address always gives the same key; a hash can't be turned back into the address without the
 * salt, which lives in a file beside the config, never in the database.
 */
public class IpKeys private constructor(private val salt: ByteArray?) {
    /** Whether addresses are hashed. */
    public val hashing: Boolean get() = salt != null

    /** The key for an address written out, as staff type it or a client connected from. */
    public fun key(address: String): String {
        val normal = normalise(address)
        val salt = salt ?: return normal
        return hex(Sha256.hmac(salt, normal.encodeToByteArray()), 16)
    }

    /** The key for where a player connected from, or `null` for one never worth keeping (loopback, the "any" address). */
    public fun keyOf(address: String?): String? {
        if (address == null || !punishable(address)) return null
        return key(address)
    }

    /** A check of the salt, stored in the database so servers with different salts notice. */
    public fun fingerprint(): String = if (hashing) key("0.0.0.0") else "raw"

    public companion object {
        /** The file the salt is kept in, in xBans' folder. */
        public const val SALT_FILE: String = "ip-salt.txt"

        /**
         * Put in xBans' folder by the owner when the salt is lost for good: at the next start, a
         * hashed database takes the salt that's there (or a new one), and the file is deleted.
         */
        public const val RESET_FILE: String = "reset-ip-salt.txt"

        /** What the console says to do when the salt can't be found again. */
        public const val LOST: String = "If it's lost for good, create an empty file named $RESET_FILE in xBans' folder and start again: " +
            "xBans starts over with a new salt, and IP bans and alts recorded before no longer match anyone."

        @kotlin.jvm.JvmStatic
        public fun raw(): IpKeys = IpKeys(null)

        @kotlin.jvm.JvmStatic
        public fun hashed(salt: ByteArray): IpKeys = IpKeys(salt.copyOf())

        /** A salt as `ip-salt.txt` holds it, 64 hex digits; `null` if [text] isn't one. */
        public fun parseSalt(text: String): ByteArray? {
            val clean = text.trim()
            if (clean.length != 64 || clean.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return ByteArray(32) { ((digit(clean[it * 2]) shl 4) or digit(clean[it * 2 + 1])).toByte() }
        }

        /** [salt] as `ip-salt.txt` holds it. */
        public fun saltText(salt: ByteArray): String = hex(salt, salt.size) + "\n"

        /** How a key is shown to staff who may see addresses (`/iphistory`): the address, or the start of its hash. */
        public fun shown(key: String?): String {
            if (key == null) return "?"
            return if (looksLikeAddress(key)) key else key.take(8) + "…"
        }

        /**
         * How a key is named where anyone may read it: the name of an IP punishment with no account,
         * which broadcasts and ban lists show. An address loses all but its first half
         * (`203.0.x.x`, `2001:db8:…`); a hash shows its start, as [shown].
         */
        public fun label(key: String?): String {
            if (key == null) return "?"
            if (!looksLikeAddress(key)) return shown(key)
            val normal = normalise(key)
            if (ipv4(normal) != null) {
                val parts = normal.split('.')
                return parts[0] + "." + parts[1] + ".x.x"
            }
            val groups = normal.split(':')
            return if (groups.size < 2) "…" else groups[0] + ":" + groups[1] + ":…"
        }

        /** Whether [text] is an IP address (v4, or v6 with colons). */
        public fun looksLikeAddress(text: String): Boolean = ipv4(text) != null || text.indexOf(':') >= 0 && ipv6(text) != null

        /** Whether [address] is one worth punishing: an address, and not loopback or "any". */
        public fun punishable(address: String): Boolean {
            ipv4(address)?.let { return it[0] != 127 && !it.all { b -> b == 0 } }
            val v6 = ipv6(address) ?: return false
            mapped(v6)?.let { return it[0] != 127 && !it.all { b -> b == 0 } }
            val loopback = (0 until 7).all { v6[it] == 0 } && v6[7] == 1
            return !loopback && !v6.all { it == 0 }
        }

        /** The address as Java writes it, so `1.2.3.4` and `::1` forms compare equal; anything else as it is. */
        public fun normalise(address: String): String {
            ipv4(address)?.let { return it.joinToString(".") }
            val v6 = ipv6(address) ?: return address
            mapped(v6)?.let { return it.joinToString(".") }
            val scope = address.substringAfter('%', "")
            return v6.joinToString(":") { it.toString(16) } + if (scope.isEmpty()) "" else "%$scope"
        }

        // Four bytes, each written as a number up to 255, or null.
        private fun ipv4(text: String): IntArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val out = IntArray(4)
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                val value = part.toInt()
                if (value > 255) return null
                out[i] = value
            }
            return out
        }

        // Eight 16-bit groups, with :: and a dotted IPv4 tail as RFC 4291 allows, or null.
        private fun ipv6(text: String): IntArray? {
            val body = text.substringBefore('%')
            if (body.length < 2 || body.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' && it != '.' }) return null
            val halves = body.split("::")
            if (halves.size > 2) return null
            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                val out = ArrayList<Int>()
                val pieces = part.split(':')
                for ((i, piece) in pieces.withIndex()) {
                    if (i == pieces.lastIndex && '.' in piece) {
                        val v4 = ipv4(piece) ?: return null
                        out += (v4[0] shl 8) or v4[1]
                        out += (v4[2] shl 8) or v4[3]
                    } else {
                        if (piece.isEmpty() || piece.length > 4) return null
                        out += piece.toIntOrNull(16) ?: return null
                    }
                }
                return out
            }
            val head = groups(halves[0]) ?: return null
            if (halves.size == 1) return if (head.size == 8) head.toIntArray() else null
            val tail = groups(halves[1]) ?: return null
            if (head.size + tail.size > 7) return null
            return (head + List(8 - head.size - tail.size) { 0 } + tail).toIntArray()
        }

        // ::ffff:a.b.c.d, which Java hands back as the IPv4 address.
        private fun mapped(v6: IntArray): IntArray? {
            if ((0 until 5).any { v6[it] != 0 } || v6[5] != 0xFFFF) return null
            return intArrayOf(v6[6] shr 8, v6[6] and 0xFF, v6[7] shr 8, v6[7] and 0xFF)
        }

        private fun digit(c: Char): Int = if (c in '0'..'9') c - '0' else c - 'a' + 10

        private fun hex(bytes: ByteArray, count: Int): String {
            val out = StringBuilder(count * 2)
            for (i in 0 until count) {
                val b = bytes[i].toInt() and 0xFF
                out.append(HEX[b shr 4]).append(HEX[b and 0xF])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}

/** SHA-256 and HMAC-SHA256 (FIPS 180-4, RFC 2104), in common Kotlin: `ips.hash` on every platform. */
internal object Sha256 {
    private val K = intArrayOf(
        0x428a2f98u.toInt(), 0x71374491u.toInt(), 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(), 0x3956c25bu.toInt(), 0x59f111f1u.toInt(), 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(), 0x12835b01u.toInt(), 0x243185beu.toInt(), 0x550c7dc3u.toInt(), 0x72be5d74u.toInt(), 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6u.toInt(), 0x240ca1ccu.toInt(), 0x2de92c6fu.toInt(), 0x4a7484aau.toInt(), 0x5cb0a9dcu.toInt(), 0x76f988dau.toInt(),
        0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(), 0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351u.toInt(), 0x14292967u.toInt(),
        0x27b70a85u.toInt(), 0x2e1b2138u.toInt(), 0x4d2c6dfcu.toInt(), 0x53380d13u.toInt(), 0x650a7354u.toInt(), 0x766a0abbu.toInt(), 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(), 0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070u.toInt(),
        0x19a4c116u.toInt(), 0x1e376c08u.toInt(), 0x2748774cu.toInt(), 0x34b0bcb5u.toInt(), 0x391c0cb3u.toInt(), 0x4ed8aa4au.toInt(), 0x5b9cca4fu.toInt(), 0x682e6ff3u.toInt(),
        0x748f82eeu.toInt(), 0x78a5636fu.toInt(), 0x84c87814u.toInt(), 0x8cc70208u.toInt(), 0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
    )

    fun digest(message: ByteArray): ByteArray {
        val h = intArrayOf(0x6a09e667u.toInt(), 0xbb67ae85u.toInt(), 0x3c6ef372u.toInt(), 0xa54ff53au.toInt(), 0x510e527fu.toInt(), 0x9b05688cu.toInt(), 0x1f83d9abu.toInt(), 0x5be0cd19u.toInt())
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 9 + 63) / 64) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in 0 until padded.size / 64) {
            for (t in 0 until 16) {
                val o = block * 64 + t * 4
                w[t] = ((padded[o].toInt() and 0xFF) shl 24) or ((padded[o + 1].toInt() and 0xFF) shl 16) or
                    ((padded[o + 2].toInt() and 0xFF) shl 8) or (padded[o + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val block = if (key.size > 64) digest(key) else key
        val k = block.copyOf(64)
        val inner = ByteArray(64 + message.size)
        val outer = ByteArray(64 + 32)
        for (i in 0 until 64) {
            inner[i] = (k[i].toInt() xor 0x36).toByte()
            outer[i] = (k[i].toInt() xor 0x5c).toByte()
        }
        message.copyInto(inner, 64)
        digest(inner).copyInto(outer, 64)
        return digest(outer)
    }
}
