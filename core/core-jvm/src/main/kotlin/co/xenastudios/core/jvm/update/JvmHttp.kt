package co.xenastudios.core.jvm.update

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.update.HttpAnswer
import co.xenastudios.core.common.update.HttpGet
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The update checker's GET on the JVM: `HttpURLConnection`, as `MclogsPaste` and the library
 * loader use, so nothing is added to the jar and it runs on Java 8. Blocking: only ever called
 * from an async task. Answers bigger than [maxBytes] are cut off there, so a broken server can't
 * fill the heap.
 */
public class JvmHttp @JvmOverloads constructor(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000,
    private val maxBytes: Int = 4 * 1024 * 1024,
) : HttpGet {
    override fun get(url: String, headers: Map<String, String>): Outcome<HttpAnswer, String> {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Outcome.Err(e.toString())
        } catch (e: ClassCastException) {
            return Outcome.Err("$url isn't HTTP")
        }
        return try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() < maxBytes) {
                    val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                String(out.toByteArray(), StandardCharsets.UTF_8)
            }.orEmpty()
            Outcome.Ok(HttpAnswer(status, body))
        } catch (e: IOException) {
            Outcome.Err(e.toString())
        } finally {
            connection.disconnect()
        }
    }
}
