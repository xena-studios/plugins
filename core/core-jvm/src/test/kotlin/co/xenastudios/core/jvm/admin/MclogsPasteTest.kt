package co.xenastudios.core.jvm.admin

import co.xenastudios.core.common.Outcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The upload, against a local server that answers as mclo.gs's API does. */
class MclogsPasteTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var received: String? = null
    private var status = 200
    private var answer = """{"success":true,"id":"abc123","url":"https:\/\/mclo.gs\/abc123","raw":"https:\/\/api.mclo.gs\/1\/raw\/abc123"}"""

    init {
        server.createContext("/1/log") { exchange ->
            received = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = answer.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    private val paste = MclogsPaste("http://127.0.0.1:${server.address.port}/")

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun uploadsTheTextAsAFormAndGivesTheLink() {
        assertEquals(Outcome.Ok("https://mclo.gs/abc123"), paste.upload("xTest 1.0 debug report\npassword: <removed> & more").get())
        val form = received!!
        assertTrue(form.startsWith("content="), form)
        assertEquals("xTest 1.0 debug report\npassword: <removed> & more", URLDecoder.decode(form.removePrefix("content="), "UTF-8"))
    }

    @Test
    fun aRefusalSaysWhy() {
        status = 400
        answer = """{"success":false,"error":"Required POST argument 'content' is empty."}"""
        assertEquals(Outcome.Err("mclo.gs answered HTTP 400: Required POST argument 'content' is empty."), paste.upload("").get())
    }

    @Test
    fun anUnreachableServiceIsAnErrorNotAThrow() {
        val port = ServerSocket(0).use { it.localPort }
        val result = MclogsPaste("http://127.0.0.1:$port", connectTimeoutMillis = 2_000).upload("text").get()
        assertTrue(result is Outcome.Err && result.error.startsWith("mclo.gs can't be reached"), result.toString())
    }

    @Test
    fun readsTheAnswersStrings() {
        assertEquals("a\"b/cé", MclogsPaste.string("""{"url":"a\"b\/cé"}""", "url"))
        assertEquals(null, MclogsPaste.string("""{"id":"x"}""", "url"))
    }
}
