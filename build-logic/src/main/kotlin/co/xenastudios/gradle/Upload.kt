package co.xenastudios.gradle

import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * One multipart upload to a store: what the release sends, so a dry run can print exactly
 * that instead of sending it. Header values that hold a token are never printed.
 */
data class Upload(
    val store: String,
    val url: String,
    val headers: Map<String, String>,
    /** Text parts, in order; a part whose name ends in "json"-like content is printed as is. */
    val fields: Map<String, String>,
    /** File parts: part name to file. */
    val files: List<Pair<String, File>>,
    /** Header names whose values are secret. */
    val secretHeaders: Set<String> = emptySet(),
    /** Field names whose values are secret (voxel.shop takes its key as a field). */
    val secretFields: Set<String> = emptySet(),
) {
    fun send(): HttpResponse<String> {
        val boundary = "xena-" + UUID.randomUUID()
        val parts = fields.map { (name, value) ->
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
        } + files.flatMap { (name, file) ->
            listOf(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n" +
                    "Content-Type: application/java-archive\r\n\r\n").toByteArray(),
                file.readBytes(),
                "\r\n".toByteArray(),
            )
        } + listOf("--$boundary--\r\n".toByteArray())
        val request = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
        headers.forEach { (k, v) -> request.header(k, v) }
        return Stores.send(request)
    }

    /** What a dry run prints: the request, with secrets replaced. */
    fun describe(): String = buildString {
        appendLine("$store: POST $url")
        headers.forEach { (k, v) -> appendLine("  $k: ${if (k in secretHeaders) "<secret>" else v}") }
        fields.forEach { (k, v) ->
            val shown = if (k in secretFields) "<secret>" else v
            if ('\n' in shown) {
                appendLine("  $k:")
                shown.lines().forEach { appendLine("    $it") }
            } else {
                appendLine("  $k: $shown")
            }
        }
        files.forEach { (k, f) -> appendLine("  $k: ${f.name} (${f.length()} bytes, sha256 ${Release.sha256(f)})") }
    }
}
