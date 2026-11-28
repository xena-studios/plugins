package co.xenastudios.xstats.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/**
 * A response made once and sent many times: its bytes, the same gzipped (`null` when that wouldn't
 * be smaller), and an ETag, so a browser that has it already gets a 304 with no body. [maxAge] is
 * seconds a browser or proxy may keep it; 0 for not at all, [Page.REVALIDATE] to keep it but ask
 * each time.
 */
public class Response(
    public val status: Int,
    public val type: String,
    public val body: ByteArray,
    public val gzip: ByteArray?,
    public val etag: String,
    public val maxAge: Int,
) {
    /** The gzipped copy's ETag: different bytes, so a different tag. */
    public val gzipEtag: String get() = etag.substring(0, etag.length - 1) + "-gz\""

    public companion object {
        public const val JSON: String = "application/json; charset=utf-8"

        public fun of(page: Page): Response = of(page.status, JSON, page.json.toByteArray(Charsets.UTF_8), page.maxAge)

        public fun error(status: Int, message: String): Response = of(Page.error(status, message))

        public fun of(status: Int, type: String, body: ByteArray, maxAge: Int): Response {
            val crc = CRC32()
            crc.update(body)
            val etag = "\"" + String.format("%08x", crc.value.toInt()) + Integer.toHexString(body.size) + "\""
            return Response(status, type, body, if (compressible(type) && body.size > 512) gzip(body) else null, etag, maxAge)
        }

        private fun compressible(type: String): Boolean =
            type.startsWith("text/") || type.startsWith("application/json") || type.startsWith("application/javascript") || type.startsWith("image/svg")

        private fun gzip(body: ByteArray): ByteArray? {
            val out = ByteArrayOutputStream(body.size / 3 + 64)
            try {
                GZIPOutputStream(out).use { it.write(body) }
            } catch (e: IOException) {
                return null
            }
            val zipped = out.toByteArray()
            return if (zipped.size < body.size) zipped else null
        }
    }
}

/**
 * At most so many requests a minute from one visitor: a count per address per minute.
 *
 * Memory is bounded without ever forgetting a visitor mid-minute: past [maxTracked] addresses, those
 * from earlier minutes are dropped (at most once a minute), and while it's still full, addresses not
 * seen yet this minute are let through uncounted. A flood of made-up addresses then resets nobody's
 * count and locks nobody out; the server's limit on requests at once still bounds what it costs.
 */
internal class RateLimit(private val perMinute: Int, private val clock: () -> Long, private val maxTracked: Int = MAX_TRACKED) {
    private class Window(val minute: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()
    private val swept = AtomicLong(-1)

    /** Counts a request, and says whether it's allowed. */
    fun allow(client: String): Boolean {
        val minute = clock() / 60_000
        if (windows.size >= maxTracked && swept.getAndSet(minute) != minute) {
            windows.values.removeIf { it.minute != minute }
        }
        if (windows.size >= maxTracked && !windows.containsKey(client)) return true
        val window = windows.compute(client) { _, old -> if (old == null || old.minute != minute) Window(minute, 1) else Window(minute, old.count + 1) }!!
        return window.count <= perMinute
    }

    val tracked: Int get() = windows.size

    /** Seconds until the minute is up, for Retry-After. */
    fun retryAfter(): Long = 60 - (clock() / 1000) % 60

    companion object {
        const val MAX_TRACKED = 10_000
    }
}

/**
 * The reverse proxies whose word on a visitor's address is believed: single addresses and CIDR
 * ranges (Cloudflare publishes its own as ranges), and the one header that carries it. Nothing here
 * looks a name up in DNS: only IP addresses written out are read.
 */
internal class Proxies private constructor(private val ranges: List<Range>, val header: String) {
    /** An address, and how many of its leading bits a range shares (all of them for one address). */
    private class Range(val address: ByteArray, val bits: Int) {
        fun contains(other: ByteArray): Boolean {
            if (other.size != address.size) return false
            val whole = bits / 8
            for (i in 0 until whole) if (address[i] != other[i]) return false
            val rest = bits % 8
            if (rest == 0) return true
            val mask = (0xFF shl (8 - rest)) and 0xFF
            return (address[whole].toInt() and mask) == (other[whole].toInt() and mask)
        }
    }

    fun trusted(address: ByteArray?): Boolean = address != null && ranges.any { it.contains(address) }

    companion object {
        const val FORWARDED_FOR = "x-forwarded-for"
        private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
        private val IPV6 = Regex("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*")
        private val HEADER = Regex("[A-Za-z0-9-]{1,64}")

        /** [entries] are `web.trusted-proxies`: addresses or CIDR ranges; [header] is `web.client-ip-header`. */
        fun of(entries: List<String>, header: String?, warn: (String) -> Unit): Proxies {
            val ranges = ArrayList<Range>()
            for (entry in entries) {
                val text = entry.trim()
                val slash = text.indexOf('/')
                val address = literal(if (slash < 0) text else text.substring(0, slash))
                val bits = if (address == null) -1 else if (slash < 0) address.size * 8 else text.substring(slash + 1).toIntOrNull() ?: -1
                if (address == null || bits < 0 || bits > address.size * 8) {
                    warn("config.yml: web.trusted-proxies: \"$text\" isn't an IP address or a range such as 173.245.48.0/20; it's ignored.")
                    continue
                }
                ranges += Range(address, bits)
            }
            var name = header?.trim()?.lowercase() ?: ""
            if (!HEADER.matches(name)) {
                warn("config.yml: web.client-ip-header: \"$header\" isn't a header name; using X-Forwarded-For.")
                name = FORWARDED_FOR
            }
            return Proxies(ranges, name)
        }

        /** An IP address written out, as bytes, or `null` for anything else: never a DNS lookup. */
        fun literal(text: String?): ByteArray? {
            if (text == null || !(IPV4.matches(text) || IPV6.matches(text))) return null
            return try {
                // Only ever given a literal here, which getByName reads without asking DNS.
                InetAddress.getByName(text).address
            } catch (e: UnknownHostException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
    }
}
