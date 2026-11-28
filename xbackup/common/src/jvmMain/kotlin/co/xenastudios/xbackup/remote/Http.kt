package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.store.Throttle
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The HTTP the remotes share: a small HTTP/1.1 client of xBackup's own, retries, safe XML,
 * percent-encoding.
 *
 * Its own client, not the JDK's: the plugin runs on Java 8 (Spigot 1.8.8's), which has no
 * `java.net.http`, and `HttpURLConnection` refuses WebDAV's methods (PROPFIND, MKCOL, MOVE) on
 * every Java. One request a connection (`Connection: close`), a body of a known length, replies
 * read whole or with chunked transfer encoding, redirects followed for reads and never from https
 * to http. TLS through the JDK's default trust and host name checks, as 1.x's client did.
 */
public object Http {
    /** How long a connection may take to open. */
    private const val CONNECT_MILLIS = 20_000

    /** How long a server may go quiet in the middle of a reply before it's given up on. Tests shorten it. */
    @Volatile
    @JvmStatic
    public var idleMillis: Int = 120_000

    /** What a request sends. */
    public sealed class Body {
        public abstract val length: Long

        public class Bytes(public val bytes: ByteArray, internal val throttle: Throttle = Throttle.NONE) : Body() {
            override val length: Long get() = bytes.size.toLong()
        }

        public class File(public val file: Path, internal val throttle: Throttle) : Body() {
            override val length: Long = Files.size(file)
        }
    }

    /** A reply: its status, headers (by lowercase name) and body. */
    public class Response(public val status: Int, public val headers: Map<String, List<String>>, public val body: ByteArray) {
        public fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

        public val text: String get() = String(body, Charsets.UTF_8)
    }

    /** Sends [method] to [uri] and reads the whole reply. */
    @JvmStatic
    @JvmOverloads
    public fun send(method: String, uri: URI, headers: Map<String, String> = emptyMap(), body: Body? = null): Response =
        exchange(method, uri, headers, body) { status, replyHeaders, input -> Response(status, replyHeaders, input.readBytes()) }

    /** A downloaded file: its status, and its body from a temporary file deleted on close. */
    public class Download(public val status: Int, public val body: InputStream)

    /** A GET for a whole file, saved to a temporary file first so a stalled body can't hang. */
    @JvmStatic
    public fun download(uri: URI, headers: Map<String, String> = emptyMap()): Download {
        val temp = Files.createTempFile("xbackup-get", ".part")
        try {
            val status = exchange("GET", uri, headers, null) { status, _, input ->
                Files.newOutputStream(temp).use { input.copyTo(it) }
                status
            }
            return Download(status, Files.newInputStream(temp, StandardOpenOption.DELETE_ON_CLOSE))
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    /** One exchange, redirects followed for GET and HEAD (never from https to http), the reply handed to [read]. */
    internal fun <T> exchange(method: String, uri: URI, headers: Map<String, String>, body: Body?, read: (Int, Map<String, List<String>>, InputStream) -> T): T {
        var at = uri
        repeat(6) {
            val result = once(method, at, headers, body, read)
            val location = result.second
            if (location == null) {
                @Suppress("UNCHECKED_CAST")
                return result.first as T
            }
            val next = at.resolve(location)
            if (at.scheme == "https" && next.scheme != "https") throw Permanent("$at redirected to $next, which isn't https")
            at = next
        }
        throw Permanent("$uri redirected too many times")
    }

    // The reply's value, or a redirect's Location (and no value).
    private fun <T> once(method: String, uri: URI, headers: Map<String, String>, body: Body?, read: (Int, Map<String, List<String>>, InputStream) -> T): Pair<T?, String?> {
        val https = when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> false
            else -> throw Permanent("$uri isn't an http or https address")
        }
        val host = uri.host ?: throw Permanent("$uri has no host")
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), CONNECT_MILLIS)
        val socket: Socket = if (https) {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            val parameters = tls.sslParameters
            // The certificate must be for this host, as an https client checks.
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = parameters
            tls
        } else {
            plain
        }
        socket.use {
            socket.soTimeout = idleMillis
            if (socket is SSLSocket) socket.startHandshake()
            val out = socket.getOutputStream().buffered(1 shl 16)
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            val defaultPort = uri.port <= 0 || port == (if (https) 443 else 80)
            head.append("Host: ").append(if (':' in host) "[$host]" else host).append(if (defaultPort) "" else ":$port").append("\r\n")
            head.append("Connection: close\r\n")
            head.append("User-Agent: xBackup\r\n")
            for ((name, value) in headers) {
                require('\r' !in value && '\n' !in value && '\r' !in name && '\n' !in name) { "a header can't hold a line break" }
                head.append(name).append(": ").append(value).append("\r\n")
            }
            if (body != null || method == "PUT" || method == "POST") head.append("Content-Length: ").append(body?.length ?: 0).append("\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            when (body) {
                is Body.Bytes -> {
                    body.throttle.acquire(body.bytes.size.toLong())
                    out.write(body.bytes)
                }
                is Body.File -> body.throttle.wrap(Files.newInputStream(body.file)).use { it.copyTo(out, 1 shl 16) }
                null -> Unit
            }
            out.flush()
            val input = BufferedInputStream(socket.getInputStream(), 1 shl 16)
            var status: Int
            var replyHeaders: Map<String, List<String>>
            while (true) {
                val statusLine = line(input) ?: throw EOFException("the server closed the connection without a reply")
                val parts = statusLine.split(' ', limit = 3)
                status = parts.getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("the server's reply isn't HTTP: ${statusLine.take(80)}")
                replyHeaders = readHeaders(input)
                // 100 Continue and other interim replies come before the real one.
                if (status !in 100..199) break
            }
            if (status in setOf(301, 302, 303, 307, 308) && (method == "GET" || method == "HEAD")) {
                val location = replyHeaders["location"]?.firstOrNull()
                if (location != null) return null to location
            }
            val content: InputStream = when {
                method == "HEAD" || status == 204 || status == 304 -> ByteArrayInputStream(ByteArray(0))
                replyHeaders["transfer-encoding"]?.any { it.lowercase().contains("chunked") } == true -> Chunked(input)
                replyHeaders["content-length"]?.firstOrNull()?.trim()?.toLongOrNull() != null -> Limited(input, replyHeaders.getValue("content-length").first().trim().toLong())
                else -> input
            }
            return read(status, replyHeaders, content) to null
        }
    }

    private fun readHeaders(input: InputStream): Map<String, List<String>> {
        val headers = LinkedHashMap<String, MutableList<String>>()
        while (true) {
            val line = line(input) ?: throw EOFException("the reply's headers end early")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) headers.getOrPut(line.substring(0, colon).trim().lowercase()) { ArrayList() } += line.substring(colon + 1).trim()
        }
    }

    // One CRLF (or LF) line, at most 64 KiB; null at the end of the stream.
    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            out.write(b)
            if (out.size() > 65_536) throw java.io.IOException("a line of the server's reply is too long")
        }
        val bytes = out.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.ISO_8859_1)
    }

    // A body of Content-Length bytes: a connection cut short is an error, not a short file.
    private class Limited(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b < 0) throw EOFException("the reply ended $left bytes early")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("the reply ended $left bytes early")
            left -= n
            return n
        }
    }

    // Chunked transfer encoding: each chunk's size in hex on a line, the chunk, CRLF; 0 ends it.
    private class Chunked(private val input: InputStream) : InputStream() {
        private var left = 0L
        private var done = false

        private fun next(): Boolean {
            if (done) return false
            if (left > 0) return true
            val size = line(input)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: throw EOFException("a chunked reply ends early")
            if (size == 0L) {
                while (line(input)?.isNotEmpty() == true) {
                    // Trailers, if any.
                }
                done = true
                return false
            }
            left = size
            return true
        }

        override fun read(): Int {
            if (!next()) return -1
            val b = input.read()
            if (b < 0) throw EOFException("a chunked reply ends early")
            if (--left == 0L) line(input)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("a chunked reply ends early")
            left -= n
            if (left == 0L) line(input)
            return n
        }
    }

    /** An HTTP call that should be tried again: the network, or the server, had a moment. */
    public fun interface Call<T> {
        public fun run(): T
    }

    /** Runs [call], trying again up to three times after a network error or a 5xx, 429 or 408. */
    @JvmStatic
    public fun <T> retry(call: Call<T>): T {
        var last: java.io.IOException? = null
        for (attempt in 0 until 4) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1000L shl attempt)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("stopped")
                }
            }
            try {
                return call.run()
            } catch (e: Transient) {
                last = e
            } catch (e: SocketTimeoutException) {
                last = java.io.IOException("the server stopped answering for ${idleMillis / 1000} s", e)
            } catch (e: ConnectException) {
                last = e
            } catch (e: java.io.IOException) {
                if (e is NoSuchFileException || e is Permanent || e is ShortFile || e is java.io.InterruptedIOException && e !is SocketTimeoutException) throw e
                last = e
            }
        }
        throw last!!
    }

    /** A failure worth trying again. */
    public class Transient(message: String) : java.io.IOException(message)

    /** The file really is shorter than asked for: trying again won't make it longer. */
    public class ShortFile(file: String, end: Long) : EOFException("$file is shorter than $end bytes")

    /** A failure that won't go away by trying again: bad credentials, a missing bucket. */
    public class Permanent(message: String) : java.io.IOException(message)

    /** The right exception for a failed response. */
    @JvmStatic
    public fun failure(what: String, status: Int, detail: String?): java.io.IOException {
        val message = "$what failed: HTTP $status" + if (detail.isNullOrBlank()) "" else " ($detail)"
        return if (status >= 500 || status == 429 || status == 408) Transient(message) else Permanent(message)
    }

    /**
     * The bytes a ranged GET asked for. A 200 means the server ignored the range and sent the
     * whole file, so the range is cut from it; a 206 must say it starts where asked.
     */
    @JvmStatic
    public fun ranged(response: Response, file: String, offset: Long, length: Int): ByteArray {
        val body = response.body
        if (response.status == 200) {
            if (body.size < offset + length) throw ShortFile(file, offset + length)
            return if (offset == 0L && body.size == length) body else body.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val range = response.header("Content-Range")
        if (range != null) {
            val match = Regex("bytes\\s+(\\d+)-(\\d+)/.*").matchEntire(range.trim())
            if (match == null || match.groupValues[1].toLong() != offset) {
                throw Transient("$file: the server sent bytes $range when asked for $offset-${offset + length - 1}")
            }
        }
        if (body.size != length) throw ShortFile(file, offset + length)
        return body
    }

    /** A Range header's value for [length] bytes from [offset]. */
    @JvmStatic
    public fun range(offset: Long, length: Int): String = "bytes=$offset-${offset + length - 1}"

    // ---- XML ----

    /** Parses XML with DTDs and external entities off, so a hostile server can't read local files. */
    @JvmStatic
    public fun xml(bytes: ByteArray): Document = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: javax.xml.parsers.ParserConfigurationException) {
        throw java.io.IOException("the server's reply isn't readable XML: ${e.message}", e)
    } catch (e: org.xml.sax.SAXException) {
        throw java.io.IOException("the server's reply isn't readable XML: ${e.message}", e)
    }

    /** Child elements with [localName], at any depth. */
    @JvmStatic
    public fun all(node: Node, localName: String): List<Element> {
        val list = if (node is Document) node.getElementsByTagNameNS("*", localName) else (node as Element).getElementsByTagNameNS("*", localName)
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** The text of the first element with [localName], or `null`. */
    @JvmStatic
    public fun text(node: Node, localName: String): String? = all(node, localName).firstOrNull()?.textContent

    // ---- encoding ----

    /**
     * Decodes a URL path's percent escapes as UTF-8. Unlike `URLDecoder`, which decodes forms, a
     * `+` stays a `+`: in a path it's a plus, not a space.
     */
    @JvmStatic
    public fun decodePath(raw: String): String {
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val high = Character.digit(raw[i + 1], 16)
                val low = Character.digit(raw[i + 2], 16)
                if (high >= 0 && low >= 0) {
                    out.write(high shl 4 or low)
                    i += 3
                    continue
                }
            }
            val codePoint = raw.codePointAt(i)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i += Character.charCount(codePoint)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** RFC 3986 percent-encoding of everything but unreserved characters (and `/` if [keepSlash]). */
    @JvmStatic
    public fun encode(text: String, keepSlash: Boolean): String {
        val out = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' || keepSlash && c == '/') {
                out.append(c)
            } else {
                out.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[b.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    /** A form's body: `a=1&b=2`, each value encoded. */
    @JvmStatic
    public fun form(values: Map<String, String>): ByteArray =
        values.entries.joinToString("&") { encode(it.key, false) + "=" + encode(it.value, false) }.toByteArray(Charsets.UTF_8)

    internal fun copy(input: InputStream, out: OutputStream) {
        input.copyTo(out, 1 shl 16)
    }
}
