package co.xenastudios.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.net.http.HttpRequest
import java.util.UUID

/**
 * Posts the jar as a new version of the plugin's voxel.shop resource, with the changelog
 * (Markdown, turned into BBCode) as the update post. Skips a version voxel.shop already has.
 */
@DisableCachingByDefault(because = "Publishes to voxel.shop")
abstract class VoxelPublish : DefaultTask() {
    /** The number at the end of the resource's link, such as 4 for `item-bridge.4`. */
    @get:Input
    abstract val resourceId: Property<Int>

    @get:Input
    abstract val version: Property<String>

    /** The update post's title, such as "xPlots 1.0.2". */
    @get:Input
    abstract val title: Property<String>

    /** The changelog section, in Markdown. */
    @get:Input
    abstract val changelog: Property<String>

    @get:Input
    abstract val beta: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** A voxel.shop API key with the "Post Updates" permission. */
    @get:Internal
    abstract val apiKey: Property<String>

    @TaskAction
    fun publish() {
        val id = resourceId.get()
        val version = version.get()
        if (Stores.voxelHas(id, version)) {
            logger.lifecycle("voxel.shop resource {} already has {}", id, version)
            return
        }
        val key = apiKey.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("VOXEL_API_KEY isn't set")
        val file = jar.get().asFile

        val boundary = "xena-" + UUID.randomUUID()
        val fields = linkedMapOf(
            "api_key" to key,
            "resource_id" to id.toString(),
            "version" to version,
            "title" to title.get(),
            "message" to BbCode.fromMarkdown(changelog.get()),
        )
        if (beta.get()) fields["beta"] = "1"
        val parts = fields.map { (name, value) ->
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
        } + listOf(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
                "Content-Type: application/java-archive\r\n\r\n").toByteArray(),
            file.readBytes(),
            "\r\n--$boundary--\r\n".toByteArray(),
        )

        val response = Stores.send(
            HttpRequest.newBuilder(URI("${Stores.VOXEL_API}postUpdate"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArrays(parts)),
        )
        val update = Stores.voxelResponse(response)["update"] as? Map<*, *>
        logger.lifecycle("Published {} to voxel.shop resource {} (update {})", version, id, update?.get("id"))
    }
}
