package co.xenastudios.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * voxel.shop's update API: a jar as a new version of a resource, with the changelog (Markdown,
 * turned into BBCode) as the update post. Shared by today's plugins ([VoxelPublish]) and the
 * rewrite's release ([VoxelReleasePublish]).
 */
object Voxel {
    fun upload(resourceId: Int, version: String, title: String, changelog: String, beta: Boolean, apiKey: String, jar: File) = Upload(
        store = "voxel.shop",
        url = "${Stores.VOXEL_API}postUpdate",
        headers = emptyMap(),
        fields = linkedMapOf(
            "api_key" to apiKey,
            "resource_id" to resourceId.toString(),
            "version" to version,
            "title" to title,
            "message" to BbCode.fromMarkdown(changelog),
        ).apply { if (beta) put("beta", "1") },
        files = listOf("file" to jar),
        secretFields = setOf("api_key"),
    )

    /** Posts the update, unless voxel.shop already has the version or the resource isn't approved yet. */
    fun publish(resourceId: Int, version: String, logger: Logger, upload: () -> Upload) {
        when (Stores.voxelState(resourceId, version)) {
            Stores.VoxelState.HAS_VERSION -> {
                logger.lifecycle("voxel.shop resource {} already has {}", resourceId, version)
                return
            }
            Stores.VoxelState.AWAITING_APPROVAL -> {
                logger.warn("voxel.shop resource {} is still waiting for approval, so {} isn't posted there", resourceId, version)
                return
            }
            Stores.VoxelState.MISSING_VERSION -> {}
        }
        val update = Stores.voxelResponse(upload().send())["update"] as? Map<*, *>
        logger.lifecycle("Published {} to voxel.shop resource {} (update {})", version, resourceId, update?.get("id"))
    }
}

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
        Voxel.publish(id, version, logger) {
            val key = apiKey.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException("VOXEL_API_KEY isn't set")
            Voxel.upload(id, version, title.get(), changelog.get(), beta.get(), key, jar.get().asFile)
        }
    }
}

/** The rewrite's release to voxel.shop: the release's Bukkit-family jar, from `release.json`. */
@DisableCachingByDefault(because = "Publishes to voxel.shop")
abstract class VoxelReleasePublish : DefaultTask() {
    /** The resource id. Unset: the release skips voxel.shop. */
    @get:Input
    @get:Optional
    abstract val resourceId: Property<Int>

    @get:Internal
    abstract val releaseDir: DirectoryProperty

    @get:Input
    abstract val dryRun: Property<Boolean>

    @get:Internal
    abstract val apiKey: Property<String>

    @TaskAction
    fun publish() {
        val dir = releaseDir.get().asFile
        val manifest = ReleaseManifest.read(dir)
        // One file per update: the Bukkit jar where there is one, else the Paper-only jar.
        val jar = manifest.files.filter { it.platform.bukkitFamily }.minByOrNull { it.platform.ordinal }
        val id = resourceId.orNull
        val dryRun = dryRun.get()
        if (jar == null || (id == null && !dryRun)) {
            logger.lifecycle(if (jar == null) "voxel.shop: no jar of this release goes there" else "voxel.shop: the plugin has no resource yet")
            return
        }
        val upload = { key: String ->
            Voxel.upload(id ?: 0, manifest.version, manifest.title, manifest.changelog,
                manifest.type != VersionType.RELEASE, key, dir.resolve(jar.file))
        }
        val preview = upload("<key>")
        StoreChecks.require("${manifest.title} on voxel.shop", StoreChecks.voxel(preview.fields, dir.resolve(jar.file)))
        if (dryRun) {
            DryRun.report(dir, "voxel", preview.describe() + "  checked: postUpdate's fields\n" +
                (if (id == null) "  (no voxel.shop resource yet: a real release skips this)\n" else ""), logger)
            return
        }
        Voxel.publish(id!!, manifest.version, logger) {
            upload(apiKey.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException("VOXEL_API_KEY isn't set"))
        }
    }
}
