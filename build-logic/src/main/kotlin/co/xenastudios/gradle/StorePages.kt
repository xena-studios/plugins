package co.xenastudios.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.net.http.HttpRequest

/** A store page: `<plugin>/modrinth.md`, Modrinth's project body, and Hangar's main page without the Modrinth-only blocks. */
object StorePage {
    private val modrinthOnly = Regex("""<!-- modrinth_exclude\.start -->[\s\S]*?<!-- modrinth_exclude\.end -->\n?""")

    fun modrinthBody(page: String): String = JsonOutput.toJson(mapOf("body" to page))

    fun hangarBody(page: String): String = JsonOutput.toJson(mapOf("content" to modrinthOnly.replace(page, "")))

    fun problems(modrinth: String, hangar: String?): List<String> =
        JsonSchema.load("modrinth-project-edit").problems(JsonSlurper().parseText(modrinth)).map { "Modrinth: $it" } +
            (hangar?.let { JsonSchema.load("hangar-page-edit").problems(JsonSlurper().parseText(it)).map { p -> "Hangar: $p" } } ?: emptyList())
}

/**
 * Publishes a rewritten plugin's store page, `<plugin>/modrinth.md`, as its Modrinth project's body
 * and its Hangar project's main page: what `modrinthSyncBody` and `syncAllPagesToHangar` do for
 * today's plugins. The Store pages workflow runs it when the file changes on main (a release pull
 * request renames a new plugin's `modrinth.draft.md` to it). `-Pxena.release.dryRun=true` checks
 * and prints the two requests instead.
 */
@DisableCachingByDefault(because = "Publishes to Modrinth and Hangar")
abstract class StorePagePublish : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val page: RegularFileProperty

    @get:Input
    abstract val modrinthSlug: Property<String>

    /** Unset until the plugin has a Hangar project: skipped. */
    @get:Input
    @get:Optional
    abstract val hangarSlug: Property<String>

    @get:Input
    abstract val dryRun: Property<Boolean>

    @get:Internal
    abstract val modrinthToken: Property<String>

    @get:Internal
    abstract val hangarApiKey: Property<String>

    @TaskAction
    fun publish() {
        val text = page.get().asFile.readText()
        val modrinth = StorePage.modrinthBody(text)
        val hangar = hangarSlug.orNull?.let { StorePage.hangarBody(text) }
        StoreChecks.require("${page.get().asFile.name}'s store pages", StorePage.problems(modrinth, hangar ?: StorePage.hangarBody(text)))
        val slug = modrinthSlug.get()
        if (dryRun.get()) {
            logger.lifecycle("Dry run, nothing sent. Modrinth would get PATCH {}project/{} ({} characters){}", Modrinth.API, slug, text.length,
                hangarSlug.orNull?.let { "; Hangar PATCH ${Hangar.API}pages/editmain/$it" } ?: "; no Hangar project yet")
            return
        }
        val token = modrinthToken.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException("MODRINTH_TOKEN isn't set")
        send("Modrinth", HttpRequest.newBuilder(URI("${Modrinth.API}project/$slug")).header("Authorization", token), modrinth)
        logger.lifecycle("Modrinth project {}'s page is {}", slug, page.get().asFile.name)
        val hangarSlug = hangarSlug.orNull ?: return logger.lifecycle("Hangar: the plugin has no project yet")
        val key = hangarApiKey.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException("HANGAR_API_TOKEN isn't set")
        send("Hangar", HttpRequest.newBuilder(URI("${Hangar.API}pages/editmain/$hangarSlug")).header("Authorization", Stores.hangarJwt(key)), hangar!!)
        logger.lifecycle("Hangar project {}'s main page is {}", hangarSlug, page.get().asFile.name)
    }

    private fun send(store: String, request: HttpRequest.Builder, body: String) {
        val response = Stores.send(request.header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)))
        if (response.statusCode() !in 200..299) throw GradleException("$store refused the page (HTTP ${response.statusCode()}): ${response.body()}")
    }
}
