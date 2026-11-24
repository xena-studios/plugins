package co.xenastudios.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks each store whether it already has a version, so a release rerun after a partial
 * failure skips the stores it reached and publishes to the rest.
 */
object Stores {
    const val USER_AGENT = "xena-studios/plugins release workflow"
    const val VOXEL_API = "https://api.voxel.shop/v1/"
    private const val MODRINTH_API = "https://api.modrinth.com/v2/"
    private const val HANGAR_API = "https://hangar.papermc.io/api/v1/"

    val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun modrinthHas(project: String, version: String, token: String): Boolean =
        exists("Modrinth", get("${MODRINTH_API}project/${enc(project)}/version/${enc(version)}", "Authorization" to token))

    fun hangarHas(project: String, version: String, apiKey: String): Boolean =
        // With the short-lived token, a project still waiting for approval is visible too.
        exists("Hangar", get("${HANGAR_API}projects/${enc(project)}/versions/${enc(version)}", "Authorization" to hangarJwt(apiKey)))

    /** Hangar trades the API key for a short-lived token, which its other calls take. */
    fun hangarJwt(apiKey: String): String {
        val auth = send(
            HttpRequest.newBuilder(URI("${HANGAR_API}authenticate?apiKey=${enc(apiKey)}"))
                .POST(HttpRequest.BodyPublishers.noBody()),
        )
        if (auth.statusCode() != 200) {
            throw GradleException("Hangar refused the API key (HTTP ${auth.statusCode()}): ${auth.body()}")
        }
        return (JsonSlurper().parseText(auth.body()) as Map<*, *>)["token"] as String
    }

    enum class VoxelState { HAS_VERSION, MISSING_VERSION, AWAITING_APPROVAL }

    fun voxelState(resourceId: Int, version: String): VoxelState {
        val response = get("${VOXEL_API}getResourceUpdates?resource_id=$resourceId&limit=50")
        // A new resource is invisible to the API, and takes no updates, until voxel.shop's
        // staff approve it.
        if ("not yet been approved" in response.body()) return VoxelState.AWAITING_APPROVAL
        val body = voxelResponse(response)
        val updates = body["updates"] as? List<*> ?: emptyList<Any>()
        return if (updates.any { (it as Map<*, *>)["version"]?.toString() == version }) {
            VoxelState.HAS_VERSION
        } else {
            VoxelState.MISSING_VERSION
        }
    }

    /** voxel.shop's `response` object, or a failure with its errors. */
    fun voxelResponse(response: HttpResponse<String>): Map<*, *> {
        val body = runCatching { (JsonSlurper().parseText(response.body()) as Map<*, *>)["response"] as Map<*, *> }
            .getOrElse { throw GradleException("voxel.shop answered HTTP ${response.statusCode()}: ${response.body()}") }
        if (body["success"] != true) {
            throw GradleException("voxel.shop refused the request: ${body["errors"]}")
        }
        return body
    }

    fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(
            request.header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(5)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(url: String, vararg headers: Pair<String, String>): HttpResponse<String> =
        send(HttpRequest.newBuilder(URI(url)).GET().apply { headers.forEach { (k, v) -> header(k, v) } })

    private fun exists(store: String, response: HttpResponse<String>): Boolean = when (response.statusCode()) {
        200 -> true
        404 -> false
        else -> throw GradleException("$store answered HTTP ${response.statusCode()}: ${response.body()}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
