package co.xenastudios.xstats.web

import co.xenastudios.core.api.PlayerRef
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The website and its JSON API, on the JDK's own HTTP server: no library in the jar. Read-only: GET
 * and HEAD only, nothing to log in to, nothing that changes anything.
 *
 * - Requests run on a pool of xStats' own threads, at most [Options.maxConcurrent] at once (1.x used
 *   virtual threads, which Java 8 doesn't have); a request that can't get a turn within two seconds
 *   gets 503, and past eight times that many waiting, new connections are closed.
 * - Each visitor (by address, or by the address a trusted proxy passes on in the one header the owner
 *   chose; an IPv6 visitor by their /64) may make [Options.perMinute] requests a minute; more get 429.
 *   Heads have a limit of their own, five times that, since a leaderboard shows many.
 * - Pages every visitor shares are made after each save and sent from memory, gzipped and with an
 *   ETag. Site files come from the jar, by exact name.
 */
public class WebServer private constructor(
    private val server: HttpServer,
    private val threads: ExecutorService,
    private val source: Source,
    private val options: Options,
    private val site: Site?,
    private val warn: (String, Throwable?) -> Unit,
) : AutoCloseable {
    /** What the server serves: the plugin's pages, or something simpler in tests. */
    public interface Source {
        /** A page made at the last save, or `null`. */
        public fun page(path: String): Response?

        public fun search(query: String?): Response

        /** A player's page, by name or uuid. Blocks for a moment: on a web thread. */
        public fun profile(who: String): Response

        /** A known player's name, or `null` if xStats doesn't know them (their head isn't fetched). */
        public fun name(player: PlayerRef): String?

        /** Where the owner's logo files are. */
        public val brandFolder: Path
    }

    public class Options(
        public val bind: String,
        public val port: Int,
        public val site: Boolean,
        public val corsOrigins: List<String>,
        public val perMinute: Int,
        public val maxConcurrent: Int,
        public val trustedProxies: List<String>,
        public val clientIpHeader: String,
        public val headTemplate: String,
        public val proxyHeads: Boolean,
    )

    private val heads = if (options.proxyHeads) Heads(options.headTemplate) else null
    private val limit = RateLimit(options.perMinute, CLOCK)
    private val headLimit = RateLimit(options.perMinute * HEADS_PER_PAGE, CLOCK)
    private val turns = Semaphore(options.maxConcurrent)
    private val proxies = Proxies.of(options.trustedProxies, options.clientIpHeader) { warn(it, null) }
    private val waiting = AtomicInteger()

    public val port: Int get() = server.address.port

    /** Stops listening and lets requests in progress finish for up to a second. */
    override fun close() {
        server.stop(1)
        threads.shutdownNow()
        try {
            threads.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            var turn = false
            try {
                // Heads have turns of their own (Heads): one waiting for a fetch mustn't hold up pages.
                val needsTurn = heads == null || !exchange.requestURI.rawPath.startsWith(Pages.BASE + "head/")
                turn = needsTurn && turns.tryAcquire(2, TimeUnit.SECONDS)
                if (needsTurn && !turn) {
                    send(exchange, Response.error(503, "Busy. Try again in a moment."), false)
                    return
                }
                serve(exchange)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                // The visitor went away mid-reply.
            } catch (e: RuntimeException) {
                warn("The web server hit an error on ${exchange.requestURI.rawPath}", e)
                try {
                    send(exchange, Response.error(500, "Something went wrong."), false)
                } catch (ignored: Exception) {
                    // Already replying, or gone.
                }
            } finally {
                if (turn) turns.release()
            }
        } finally {
            exchange.close()
        }
    }

    private fun serve(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.rawPath
        val api = path.startsWith("/api/") || path == "/api"
        // Before the limit, so a site elsewhere can read a 429 too.
        if (api) cors(exchange)
        val which = if (path.startsWith(Pages.BASE + "head/")) headLimit else limit
        if (!which.allow(client(exchange))) {
            exchange.responseHeaders.set("Retry-After", which.retryAfter().toString())
            send(exchange, Response.error(429, "Too many requests. Wait a minute."), false)
            return
        }
        if (method == "OPTIONS") {
            exchange.responseHeaders.set("Allow", "GET, HEAD, OPTIONS")
            exchange.sendResponseHeaders(204, -1)
            return
        }
        val head = method == "HEAD"
        if (!head && method != "GET") {
            exchange.responseHeaders.set("Allow", "GET, HEAD, OPTIONS")
            send(exchange, Response.error(405, "Only GET and HEAD: the API is read-only."), false)
            return
        }
        val response = when {
            api -> api(path, exchange.requestURI.rawQuery)
            path.startsWith("/brand/") -> brand(path.substring("/brand/".length))
            else -> site?.get(path)
        } ?: Response.error(404, "Not found.")
        if (response.type.startsWith("text/html")) exchange.responseHeaders.set("Content-Security-Policy", SITE_POLICY)
        send(exchange, response, head)
    }

    private fun api(path: String, query: String?): Response? {
        if (path == "/api" || path == "/api/" || path == Pages.BASE) return INDEX
        if (!path.startsWith(Pages.BASE)) return null
        val rest = path.substring(Pages.BASE.length)
        if (rest == "search") return source.search(parameter(query, "q"))
        if (rest.startsWith("player/")) {
            val who = decoded(rest.substring("player/".length))
            return if (who == null || who.isEmpty() || who.length > 36) null else source.profile(who)
        }
        if (rest.startsWith("head/")) return head(rest.substring("head/".length))
        return source.page(path)
    }

    private fun head(id: String): Response? {
        val heads = heads ?: return null
        if (!UUID_TEXT.matches(id)) return null
        val player = PlayerRef.parseOrNull(id) ?: return null
        val name = source.name(player) ?: return null
        return heads.get(player, name)
    }

    /** The owner's logo from plugins/xStats/web/, by exact file name, read each time so a new logo shows without a restart. */
    private fun brand(name: String): Response? {
        if (!BRAND_FILE.matches(name)) return null
        val folder = source.brandFolder.toAbsolutePath().normalize()
        val path = folder.resolve(name).normalize()
        return try {
            if (!path.startsWith(folder) || !Files.isRegularFile(path) || Files.size(path) > 1024 * 1024) return null
            val lower = name.lowercase()
            val type = when {
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                else -> "image/jpeg"
            }
            Response.of(200, type, Files.readAllBytes(path), 3600)
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Who's asking, for the rate limit: the connection's address, or for a trusted proxy, the visitor's
     * address it passes on in the one header the owner chose. Only that header is read: any other could
     * have come from the visitor, through the proxy, unchanged.
     */
    internal fun client(exchange: HttpExchange): String {
        val remote = exchange.remoteAddress.address.address
        if (!proxies.trusted(remote)) return key(remote)
        val values = exchange.requestHeaders[proxies.header]
        if (values == null || values.isEmpty()) return key(remote)
        if (proxies.header != Proxies.FORWARDED_FOR) {
            // A header the proxy sets alone, such as CF-Connecting-IP: one address, its last if repeated.
            return key(Proxies.literal(withoutPort(values.last().trim())) ?: remote)
        }
        // The nearest address that isn't one of our proxies: anything before it, the visitor could
        // have made up. Repeated headers count as one list, in order.
        val hops = values.joinToString(",").split(',')
        for (i in hops.indices.reversed()) {
            val text = hops[i].trim()
            if (text.isEmpty()) continue
            // Not an address: nothing further left can be believed.
            val hop = Proxies.literal(withoutPort(text)) ?: break
            if (!proxies.trusted(hop)) return key(hop)
        }
        return key(remote)
    }

    private fun cors(exchange: HttpExchange) {
        val headers = exchange.responseHeaders
        val origins = options.corsOrigins
        if ("*" in origins) {
            headers.set("Access-Control-Allow-Origin", "*")
        } else {
            val origin = exchange.requestHeaders.getFirst("Origin")
            if (origin != null && origin in origins) headers.set("Access-Control-Allow-Origin", origin)
            headers.add("Vary", "Origin")
        }
        headers.set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        // So a site hosted elsewhere can send If-None-Match for a 304, and read the ETag and a 429's Retry-After.
        headers.set("Access-Control-Allow-Headers", "If-None-Match")
        headers.set("Access-Control-Expose-Headers", "ETag, Retry-After")
        headers.set("Access-Control-Max-Age", "86400")
    }

    public companion object {
        /** Heads may be asked for this many times more often than pages: a leaderboard shows up to a hundred. */
        internal const val HEADS_PER_PAGE = 5

        private val CLOCK: () -> Long = System::currentTimeMillis
        private val BRAND_FILE = Regex("[A-Za-z0-9_.-]+\\.(png|jpe?g|svg|webp)")
        private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private const val SITE_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' https: data:;" +
            " connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'"
        private val INDEX: Response by lazy { Response.of(Pages.INDEX) }

        /** Starts listening, or throws if the port is taken or the address isn't this machine's. [Options.port] 0 is any free port, as the self-test uses. */
        public fun start(options: Options, source: Source, loader: ClassLoader, warn: (String, Throwable?) -> Unit): WebServer {
            // The JDK's server waits forever for a slow request unless told otherwise. These are the
            // JVM's own settings, read once when its server first loads, so they also apply to any
            // other plugin's, and are ignored if another plugin's server loaded first: set only if
            // nobody has. They don't stop a slow trickle of bytes; a reverse proxy does.
            defaultProperty("sun.net.httpserver.maxReqTime", "15")
            defaultProperty("sun.net.httpserver.maxRspTime", "60")
            val site = if (options.site) Site.load(loader) else null
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(options.bind), options.port), 64)
            val number = AtomicInteger()
            val threads = ThreadPoolExecutor(
                options.maxConcurrent, options.maxConcurrent, 30, TimeUnit.SECONDS, LinkedBlockingQueue(),
            ) { runnable -> Thread(runnable, "xStats web " + number.incrementAndGet()).also { it.isDaemon = true } }
            threads.allowCoreThreadTimeOut(true)
            val web = WebServer(server, threads, source, options, site, warn)
            val most = options.maxConcurrent * 8
            server.executor = java.util.concurrent.Executor { task ->
                if (web.waiting.incrementAndGet() > most) {
                    web.waiting.decrementAndGet()
                    throw RejectedExecutionException("too many requests waiting")
                }
                threads.execute {
                    try {
                        task.run()
                    } finally {
                        web.waiting.decrementAndGet()
                    }
                }
            }
            server.createContext("/") { web.handle(it) }
            server.start()
            return web
        }

        private fun defaultProperty(key: String, value: String) {
            if (System.getProperty(key) == null) System.setProperty(key, value)
        }

        internal fun parameter(query: String?, name: String): String? {
            if (query == null) return null
            for (pair in query.split('&')) {
                val equals = pair.indexOf('=')
                if (equals > 0 && pair.substring(0, equals) == name) {
                    return try {
                        URLDecoder.decode(pair.substring(equals + 1), "UTF-8")
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            }
            return null
        }

        // A path segment as the site sends it, percent-encoded: "%2BSteve" is a Bedrock name with
        // Floodgate's + prefix. A + itself stays a +, as in a path. Null if it's encoded wrong.
        private fun decoded(segment: String): String? = try {
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }

        /**
         * An address a proxy passed on, without the port some add: `1.2.3.4:5678` and
         * `[2001:db8::1]:443` are read as `1.2.3.4` and `2001:db8::1`. A bare IPv6 address has several
         * colons and is left as it is.
         */
        internal fun withoutPort(text: String): String {
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                return if (close > 0 && (close == text.length - 1 || text[close + 1] == ':')) text.substring(1, close) else text
            }
            val colon = text.indexOf(':')
            return if (colon > 0 && colon == text.lastIndexOf(':')) text.substring(0, colon) else text
        }

        /** The rate limit's key for an address: itself, or for IPv6, its /64, which one visitor usually has whole. */
        internal fun key(address: ByteArray): String {
            val hex = StringBuilder()
            val count = if (address.size == 16) 8 else address.size
            for (i in 0 until count) hex.append(String.format("%02x", address[i].toInt() and 0xFF))
            return if (address.size == 16) "$hex::/64" else hex.toString()
        }

        /** Whether Accept-Encoding allows gzip: listed (or `*`) without q=0. */
        internal fun acceptsGzip(headers: List<String>?): Boolean {
            if (headers == null) return false
            var any: Boolean? = null
            for (header in headers) {
                for (part in header.split(',')) {
                    val pieces = part.split(';')
                    val coding = pieces[0].trim().lowercase()
                    var allowed = true
                    for (i in 1 until pieces.size) {
                        val parameter = pieces[i].trim().lowercase()
                        if (parameter.startsWith("q=")) allowed = (parameter.substring(2).toDoubleOrNull() ?: 0.0) > 0
                    }
                    if (coding == "gzip") return allowed
                    if (coding == "*") any = allowed
                }
            }
            return any == true
        }

        private fun send(exchange: HttpExchange, response: Response, head: Boolean) {
            val headers = exchange.responseHeaders
            headers.set("Content-Type", response.type)
            headers.set("X-Content-Type-Options", "nosniff")
            headers.set("Referrer-Policy", "no-referrer")
            headers.set(
                "Cache-Control",
                when {
                    response.maxAge > 0 -> "public, max-age=${response.maxAge}"
                    response.maxAge == Page.REVALIDATE -> "public, no-cache"
                    else -> "no-store"
                },
            )
            // An SVG opened on its own can run scripts: not from here.
            if (response.type.startsWith("image/svg")) headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
            var zip = false
            if (response.gzip != null) {
                // Also on a 304, so a cache keeps the plain and gzipped copies apart.
                headers.add("Vary", "Accept-Encoding")
                zip = acceptsGzip(exchange.requestHeaders["Accept-Encoding"])
            }
            if (response.status == 200) {
                // The gzipped copy is different bytes, so it has an ETag of its own.
                val etag = if (zip) response.gzipEtag else response.etag
                headers.set("ETag", etag)
                val match = exchange.requestHeaders.getFirst("If-None-Match")
                if (match != null && etag in match) {
                    exchange.sendResponseHeaders(304, -1)
                    return
                }
            }
            var body = response.body
            if (zip) {
                headers.set("Content-Encoding", "gzip")
                body = response.gzip!!
            }
            if (head || body.isEmpty()) {
                if (head) headers.set("Content-Length", body.size.toString())
                exchange.sendResponseHeaders(response.status, -1)
                return
            }
            exchange.sendResponseHeaders(response.status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }
}

/**
 * The website's files, read from the jar once and kept in memory. Only these exact paths are ever
 * served, looked up in a map: no path from a request ever reaches the disk, so there's nothing to
 * traverse.
 */
public class Site private constructor(private val files: Map<String, Response>) {
    public fun get(path: String): Response? = files[path]

    public companion object {
        /** Every file in the jar's web/ folder, and its type. The build copies them from xstats/web/. */
        public val FILES: Map<String, String> = linkedMapOf(
            "index.html" to "text/html; charset=utf-8",
            "app.js" to "application/javascript; charset=utf-8",
            "style.css" to "text/css; charset=utf-8",
            "favicon.svg" to "image/svg+xml",
        )

        public fun load(loader: ClassLoader): Site {
            val files = HashMap<String, Response>()
            for ((name, type) in FILES) files["/$name"] = Response.of(200, type, read(loader, name), 300)
            files["/"] = files.getValue("/index.html")
            // The built-in site reads the API from where it was loaded. A copy hosted elsewhere has its own config.json.
            files["/config.json"] = Response.of(200, Response.JSON, "{\"api\":\"\"}".toByteArray(), 300)
            return Site(files)
        }

        /** The raw bytes of a site file, for serving and exporting. */
        public fun read(loader: ClassLoader, name: String): ByteArray {
            val input: InputStream = loader.getResourceAsStream("web/$name") ?: throw IOException("the jar is missing web/$name")
            return input.use { it.readBytes() }
        }
    }
}
