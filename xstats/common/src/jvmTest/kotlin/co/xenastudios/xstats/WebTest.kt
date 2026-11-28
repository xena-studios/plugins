package co.xenastudios.xstats

import co.xenastudios.core.common.Outcome
import co.xenastudios.xstats.web.Pages
import co.xenastudios.xstats.web.Proxies
import co.xenastudios.xstats.web.RateLimit
import co.xenastudios.xstats.web.WebServer
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The website: its self-test (the same as /xstats web test), its pages' JSON, privacy, and the limits. */
class WebTest {
    private val harness = Harness(extras = { JvmExtras(it.folder, { null }, it.folder, { null }, WebTest::class.java.classLoader) }).start()
    private val extras = harness.extras as JvmExtras

    @AfterTest
    fun stop() {
        harness.stop()
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to (stream?.use { String(it.readBytes()) } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun theSelfTestPasses() {
        val (count, failures) = extras.webTest(harness.plugin)
        assertEquals(11, count)
        assertEquals(emptyList(), failures)
    }

    @Test
    fun pagesShowWhatTheSiteMayAndNothingPrivate() {
        harness.join("Ann").counters["player_kills"] = 5
        harness.join("Ben").counters["player_kills"] = 3
        harness.save()
        val pages = extras.pages(harness.plugin)
        harness.plugin.onDatabase { _ -> pages.render() }
        harness.settle()
        val server = WebServer.start(extras.options(harness.plugin, "127.0.0.1", 0, 1000, test = true), extras.Source(harness.plugin), WebTest::class.java.classLoader) { _, _ -> }
        try {
            val (status, board) = get(server.port, "/api/v1/leaderboard/kills/all")
            assertEquals(200, status)
            assertTrue("\"name\":\"Ann\"" in board && "\"rank\":1" in board, board)
            val (_, site) = get(server.port, "/api/v1/server")
            assertTrue("\"players\":2" in site && "\"online\":2" in site, site)
            assertEquals(200, get(server.port, "/api/v1/player/Ann").first)
            assertTrue("\"Ann\"" in get(server.port, "/api/v1/search?q=a").second)
            // Ann goes private: her page and her place go at once, not at the next save.
            harness.run("stats private", harness.server.source("Ann"))
            harness.settle()
            assertEquals(404, get(server.port, "/api/v1/player/Ann").first)
            val after = get(server.port, "/api/v1/leaderboard/kills/all").second
            assertFalse("Ann" in after, after)
            assertTrue("\"name\":\"Ben\",\"value\":3" in after.replace("\"uuid\":\"${FakeStatsServer.ref("Ben")}\",", "") || "\"rank\":1" in after, after)
            assertFalse("Ann" in get(server.port, "/api/v1/search?q=a").second)
            assertEquals(404, get(server.port, "/../config.yml").first)
        } finally {
            server.close()
        }
    }

    @Test
    fun theExportPointsAtThePublicUrl() {
        harness.reload("web:\n  public-url: \"https://stats.example.com/\"\n")
        val done = extras.webExport(harness.plugin)
        assertTrue(done is Outcome.Ok, done.toString())
        val config = String(Files.readAllBytes(harness.folder.resolve("site/config.json")))
        assertEquals("{\"api\":\"https://stats.example.com\"}\n", config)
        assertTrue(Files.exists(harness.folder.resolve("site/index.html")))
    }

    @Test
    fun limitsAndProxies() {
        var now = 0L
        val limit = RateLimit(2, { now }, maxTracked = 2)
        assertTrue(limit.allow("a") && limit.allow("a"))
        assertFalse(limit.allow("a"))
        assertTrue(limit.allow("b"))
        // Full: someone new this minute is let through uncounted, never lumped in with others.
        assertTrue(limit.allow("c") && limit.allow("c") && limit.allow("c"))
        now = 60_000
        assertTrue(limit.allow("a"))
        val proxies = Proxies.of(listOf("173.245.48.0/20", "10.0.0.1", "nonsense"), "X-Forwarded-For") { }
        assertTrue(proxies.trusted(Proxies.literal("173.245.50.9")))
        assertFalse(proxies.trusted(Proxies.literal("173.245.64.1")))
        assertNull(Proxies.literal("example.com"), "never a DNS lookup")
        assertEquals("1.2.3.4", WebServer.withoutPort("1.2.3.4:5678"))
        assertEquals("2001:db8::1", WebServer.withoutPort("[2001:db8::1]:443"))
        assertEquals("2001:db8::1", WebServer.withoutPort("2001:db8::1"))
        assertEquals("20010db800000000::/64", WebServer.key(Proxies.literal("2001:db8::5")!!))
        assertTrue(WebServer.acceptsGzip(listOf("br, gzip;q=0.5")))
        assertFalse(WebServer.acceptsGzip(listOf("gzip;q=0, *")))
        assertEquals(Pages.BASE, "/api/v1/")
    }
}
