package co.xenastudios.core.jvm.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.PasteService
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * `/<plugin> debug`'s uploads to mclo.gs (or a server that speaks its API, at [endpoint]):
 * `POST /1/log` with the text as the form field `content`, answered with JSON whose `url` is
 * the paste's page. mclo.gs is what Minecraft support channels already use; it keeps a paste
 * for 90 days and hides IP addresses in it as well.
 *
 * The upload waits for the network on the calling thread, which is an async task's
 * ([co.xenastudios.core.common.admin.Debug.publish]); it's done when the returned [Pending] is
 * made. `HttpURLConnection`, as the library loader uses, so nothing is added to the jar and it
 * runs on Java 8.
 */
public class MclogsPaste @JvmOverloads constructor(
    /** The API's address, without the path: `https://api.mclo.gs`. */
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000,
) : PasteService {
    override val name: String get() = "mclo.gs"

    override fun upload(text: String): Pending<Outcome<String, String>> = pendingOf(post(text))

    private fun post(text: String): Outcome<String, String> {
        val body = ("content=" + URLEncoder.encode(text, "UTF-8")).toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_BYTES) return Outcome.Err("the report is ${body.size / 1024} KiB, more than $name takes")
        val connection = try {
            URL(endpoint.trimEnd('/') + "/1/log").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Outcome.Err("$name can't be reached: $e")
        }
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "xena-debug")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val answer = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
                .orEmpty()
            val url = string(answer, "url")
            when {
                status == HttpURLConnection.HTTP_OK && url != null -> Outcome.Ok(url)
                else -> Outcome.Err("$name answered HTTP $status" + (string(answer, "error")?.let { ": $it" } ?: ""))
            }
        } catch (e: IOException) {
            Outcome.Err("$name can't be reached: $e")
        } finally {
            connection.disconnect()
        }
    }

    public companion object {
        /** mclo.gs's API. */
        public const val DEFAULT_ENDPOINT: String = "https://api.mclo.gs"

        // mclo.gs refuses more than 10 MiB.
        private const val MAX_BYTES = 10 * 1024 * 1024

        /** The string value of [key] in a flat JSON object, unescaped, or `null`. Enough for mclo.gs's two answers. */
        internal fun string(json: String, key: String): String? {
            val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
            return Regex("\\\\(u[0-9a-fA-F]{4}|.)").replace(match.groupValues[1]) {
                val escaped = it.groupValues[1]
                when (escaped[0]) {
                    'u' -> escaped.substring(1).toInt(16).toChar().toString()
                    'n' -> "\n"
                    't' -> "\t"
                    else -> escaped
                }
            }
        }
    }
}
