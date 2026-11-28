package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.store.Throttle
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** xBackup's own HTTP/1.1 client, the one every HTTP remote talks through (1.x's HttpTest, on 2.0's client). */
class HttpTest {
    @Test
    fun aReplyThatStopsArrivingIsGivenUp() {
        // Headers, half a body, then nothing: the connection stays open, as a dead link would.
        ServerSocket(0).use { server ->
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(4096))
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nhalf".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        Thread.sleep(30_000)
                    }
                } catch (e: Exception) {
                    // Done.
                }
            }.apply { isDaemon = true; start() }
            val before = Http.idleMillis
            Http.idleMillis = 1000
            try {
                val start = System.nanoTime()
                assertFailsWith<IOException> { Http.send("GET", URI.create("http://127.0.0.1:${server.localPort}/x")) }
                assertTrue(System.nanoTime() - start < 10_000_000_000L, "gave up in time")
            } finally {
                Http.idleMillis = before
                thread.interrupt()
            }
        }
    }

    private fun reply(status: Int, body: ByteArray, contentRange: String?) =
        Http.Response(status, if (contentRange == null) emptyMap() else mapOf("content-range" to listOf(contentRange)), body)

    @Test
    fun aRangeComesFromWhereItWasAskedFor() {
        val file = ByteArray(100) { it.toByte() }
        // A server that ignores Range sends the whole file with a 200, even when it happens to be
        // as long as the range asked for.
        assertContentEquals(file.copyOfRange(10, 20), Http.ranged(reply(200, file, null), "f", 10, 10))
        assertFailsWith<Http.ShortFile> { Http.ranged(reply(200, file.copyOfRange(0, 10), null), "f", 10, 10) }
        val part = file.copyOfRange(10, 20)
        assertContentEquals(part, Http.ranged(reply(206, part, "bytes 10-19/100"), "f", 10, 10))
        assertFailsWith<Http.Transient> { Http.ranged(reply(206, part, "bytes 0-9/100"), "f", 10, 10) }
        assertFailsWith<Http.ShortFile> { Http.ranged(reply(206, ByteArray(4), "bytes 10-13/14"), "f", 10, 10) }
    }

    @Test
    fun aShortFileIsNotTriedAgain() {
        var calls = 0
        assertFailsWith<Http.ShortFile> { Http.retry<Unit> { calls++; throw Http.ShortFile("f", 10) } }
        assertEquals(1, calls)
    }

    @Test
    fun pathsDecodeWithoutTurningPlusIntoSpace() {
        assertEquals("/dav/a+b c/ü", Http.decodePath("/dav/a+b%20c/%C3%BC"))
        assertEquals("/50%", Http.decodePath("/50%"))
    }

    @Test
    fun aChunkedReplyAndARedirectedGetArriveWhole() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "/file")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, 0) // chunked
            exchange.responseBody.use { out -> repeat(100) { out.write(ByteArray(1000) { it.toByte() }) } }
        }
        server.start()
        try {
            val response = Http.send("GET", URI.create("http://127.0.0.1:${server.address.port}/moved"))
            assertEquals(200, response.status)
            assertEquals(100_000, response.body.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun webDavServedBelowTheRootListsOnlyItsFolder() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val asked = CopyOnWriteArrayList<String>()
        val base = "/remote.php/dav/files/u/xbackup/"
        val listings = mapOf(
            base to entries(base, true) + entries(base + "config", false) + entries(base + "data/", true) + entries(base + "a+b", false),
            base + "data/" to entries(base + "data/", true) + entries(base + "data/ab/", true),
            base + "data/ab/" to entries(base + "data/ab/", true) + entries(base + "data/ab/abcd", false),
        )
        server.createContext("/") { exchange ->
            asked += exchange.requestMethod + " " + exchange.requestURI.rawPath
            val listing = listings[exchange.requestURI.rawPath]
            val body = if (listing == null) ByteArray(0) else "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">$listing</d:multistatus>".toByteArray()
            exchange.sendResponseHeaders(if (listing == null) 404 else 207, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
            exchange.close()
        }
        server.start()
        try {
            val settings = RemoteConfig("webdav", true, "xbackup", "http://127.0.0.1:${server.address.port}/remote.php/dav/files/u/", "", "", false, "", "", "", 0, "", "", "", "", "")
            val names = WebDavBackend("nextcloud", settings, Throttle.NONE).list("").map { it.name }.sorted()
            assertEquals(listOf("a+b", "config", "data/ab/abcd"), names)
            for (request in asked) assertTrue(base in request, "listed outside the folder: $request")
            assertFalse("PROPFIND /" in asked)
        } finally {
            server.stop(0)
        }
    }

    private fun entries(href: String, folder: Boolean): String =
        "<d:response><d:href>${href.replace("+", "%2B")}</d:href><d:propstat><d:prop>" +
            (if (folder) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getcontentlength>7</d:getcontentlength>") +
            "</d:prop></d:propstat></d:response>"
}
