package co.xenastudios.core.jvm.update

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.update.ModrinthReleases
import co.xenastudios.core.common.update.UpdateChannel
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateNotices
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.testing.FakePlatform
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The update checker end to end over real HTTP, against a local server that answers as Modrinth's API does. */
class JvmUpdatesTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val queries = mutableListOf<String>()
    private val agents = mutableListOf<String?>()
    private var status = 200
    private var answer = """[
        {"id":"AbC1","version_number":"3.1.0-beta.1","version_type":"beta"},
        {"id":"XyZ9","version_number":"3.0.0","version_type":"release"}
    ]"""

    init {
        server.createContext("/v2/project/xtest/version") { exchange ->
            queries += URLDecoder.decode(exchange.requestURI.rawQuery, "UTF-8")
            agents += exchange.requestHeaders.getFirst("User-Agent")
            val body = answer.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    private val endpoint = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun findsTheNewestReleaseOverHttp() {
        val platform = FakePlatform()
        val console = mutableListOf<String>()
        val notices = object : UpdateNotices {
            override fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>) {
                console += placeholders.entries.joinToString { "${it.key}=${(it.value as Placeholder.Text).text}" }
            }
            override fun mayKnow(player: PlayerRef) = true
        }
        val source = ModrinthReleases("xtest", "fabric", "26.3", "2.0.0+fabric-26.3", JvmHttp(), modrinth = endpoint)
        val checker = UpdateChecker(platform, "xTest", "2.0.0+fabric-26.3", source, notices, offline = false)
        checker.apply(UpdateSettings(true, UpdateChannel.RELEASE))
        platform.scheduler.runPending()
        assertEquals(listOf("loaders=[\"fabric\"]&game_versions=[\"26.3\"]&include_changelog=false"), queries)
        assertEquals(listOf<String?>("xena-studios/xtest/2.0.0+fabric-26.3 (contact@xenastudios.co)"), agents)
        assertEquals(
            listOf("plugin=xTest, current=2.0.0+fabric-26.3, latest=3.0.0, url=https://modrinth.com/project/xtest/version/XyZ9"),
            console,
        )
    }

    @Test
    fun answersAndFailuresComeBackAsValues() {
        status = 500
        answer = "oops"
        val answered = JvmHttp().get("$endpoint/v2/project/xtest/version?x=1", emptyMap())
        assertEquals(500, (answered as Outcome.Ok).value.status)
        assertEquals("oops", answered.value.body)
        val port = ServerSocket(0).use { it.localPort }
        val refused = JvmHttp(connectTimeoutMillis = 2_000).get("http://127.0.0.1:$port/", emptyMap())
        assertTrue(refused is Outcome.Err, refused.toString())
    }

    @Test
    fun aBigAnswerIsCutOff() {
        answer = "x".repeat(10_000)
        val answered = JvmHttp(maxBytes = 1_000).get("$endpoint/v2/project/xtest/version?x=1", emptyMap())
        assertEquals(1_000, (answered as Outcome.Ok).value.body.length)
    }
}
