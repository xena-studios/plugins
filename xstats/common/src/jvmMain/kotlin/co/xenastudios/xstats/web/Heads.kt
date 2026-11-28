package co.xenastudios.xstats.web

import co.xenastudios.core.api.PlayerRef
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Players' heads fetched by xStats and kept, for owners who'd rather visitors' browsers never contact
 * a head service. Only heads of players xStats knows are fetched, at most [FETCHING] at a time (up to
 * [MAX_WAITING] others wait up to five seconds for a turn, the rest get none), each at most 64 KiB,
 * and kept a day, up to 8 MiB of them. Redirects are followed only on the same host. A head request
 * doesn't take one of the web server's turns, so heads waiting here never hold up pages. Through
 * `HttpURLConnection`, which every Java has (Spigot 1.13 runs on 8).
 */
internal class Heads(private val template: String) {
    private class Head(val at: Long, val response: Response)

    private val fetching = Semaphore(FETCHING)
    private val waiting = AtomicInteger()

    // Most recently used last; guarded by itself.
    private val kept = LinkedHashMap<PlayerRef, Head>(64, 0.75f, true)
    private var keptBytes = 0L

    // Heads the service wouldn't give lately, so a missing one isn't asked for on every view. Bounded: the oldest go first.
    private val failed = object : LinkedHashMap<PlayerRef, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlayerRef, Long>?): Boolean = size > 10_000
    }

    private fun kept(player: PlayerRef): Head? = synchronized(kept) { kept[player] }

    private fun keep(player: PlayerRef, response: Response) {
        synchronized(kept) {
            val old = kept.put(player, Head(System.currentTimeMillis(), response))
            keptBytes += response.body.size - (old?.response?.body?.size ?: 0)
            val eldest = kept.values.iterator()
            while (keptBytes > MAX_KEPT_BYTES && eldest.hasNext()) {
                keptBytes -= eldest.next().response.body.size
                eldest.remove()
            }
        }
    }

    private fun failedLately(player: PlayerRef): Boolean = synchronized(failed) {
        val at = failed[player]
        at != null && System.currentTimeMillis() - at < FAILED_MS
    }

    private fun failed(player: PlayerRef) {
        synchronized(failed) { failed[player] = System.currentTimeMillis() }
    }

    /** The head, fetched if it isn't kept, or `null` if it couldn't be. Blocks: on a web thread only. */
    fun get(player: PlayerRef, name: String?): Response? {
        val head = kept(player)
        if (head != null && System.currentTimeMillis() - head.at < KEEP_MS) return head.response
        val old = head?.response
        if (failedLately(player)) return old
        if (waiting.incrementAndGet() > MAX_WAITING) {
            waiting.decrementAndGet()
            return old
        }
        try {
            // A first visit to a leaderboard asks for many heads at once: wait for a turn.
            if (!fetching.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) return old
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return old
        } finally {
            waiting.decrementAndGet()
        }
        try {
            // Another request may have fetched it while this one waited.
            kept(player)?.let { if (System.currentTimeMillis() - it.at < KEEP_MS) return it.response }
            val url = template.replace("{uuid}", player.toString()).replace("{name}", name ?: player.toString()).replace("{size}", SIZE.toString())
            val made = fetch(URI.create(url))
            if (made == null) {
                failed(player)
                return old
            }
            keep(player, made)
            return made
        } catch (e: IOException) {
            failed(player)
            return old
        } catch (e: IllegalArgumentException) {
            failed(player)
            return old
        } finally {
            fetching.release()
        }
    }

    /** An image from the head service, or `null`. Follows a redirect only to the same host. */
    private fun fetch(uri: URI): Response? {
        var current = uri
        for (hop in 0..MAX_REDIRECTS) {
            if (current.scheme != "https" && current.scheme != "http") return null
            val connection = URL(current.toString()).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "xStats")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    val next = current.resolve(location)
                    if (next.host != uri.host) return null
                    current = next
                    continue
                }
                val type = connection.contentType ?: ""
                if (status != 200 || !(type.startsWith("image/png") || type.startsWith("image/jpeg") || type.startsWith("image/webp"))) return null
                // Read at most one byte past the limit: a bigger one is refused without being held whole.
                val bytes = connection.inputStream.use { readAtMost(it, MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) return null
                return Response.of(200, type.substringBefore(';').trim(), bytes, 86_400)
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        const val MAX_KEPT_BYTES = 8L * 1024 * 1024
        const val KEEP_MS = 86_400_000L
        const val SIZE = 64
        const val FETCHING = 4
        const val WAIT_SECONDS = 5L

        /** Requests that may wait for a fetch at once: a few leaderboards' worth. */
        const val MAX_WAITING = 64
        private const val MAX_REDIRECTS = 3
        private const val FAILED_MS = 10 * 60_000L
    }
}
