import co.xenastudios.gradle.ReleasePlatform
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import javax.inject.Inject

/** What a plugin releases, for the `xena.release` convention: its jars and its store projects. */
abstract class XenaReleaseExtension @Inject constructor(objects: ObjectFactory) {
    /** The tag's prefix (`xbans` for `xbans-v2.0.0`) and the brand catalog's id. Defaults to the project's name. */
    abstract val pluginId: Property<String>

    /** The name in titles ("xBans 2.0.0") when the brand catalog doesn't have the plugin. */
    abstract val displayName: Property<String>

    /** Whether the Bukkit-family jars run on Folia, for Modrinth's loaders. */
    abstract val folia: Property<Boolean>

    /** The Modrinth project slug. Defaults to the plugin id. */
    abstract val modrinthId: Property<String>

    /** The Hangar project slug, such as `xPlots`. Unset until the project exists: releases skip Hangar. */
    abstract val hangarId: Property<String>

    /** The voxel.shop resource id. Unset until the resource exists: releases skip voxel.shop. */
    abstract val voxelId: Property<Int>

    /** The CurseForge project ids, one per class (M0): Bukkit Plugins on dev.bukkit, mods, Hytale. Each unset until it exists. */
    abstract val curseForgeBukkitId: Property<Int>
    abstract val curseForgeModsId: Property<Int>
    abstract val curseForgeHytaleId: Property<Int>

    /** Every jar the plugin ships, one per platform (and per Minecraft line, for mods). */
    val jars: NamedDomainObjectContainer<ReleaseJar> = objects.domainObjectContainer(ReleaseJar::class.java)

    fun jar(name: String, action: Action<ReleaseJar>): NamedDomainObjectProvider<ReleaseJar> = jars.register(name, action)
}

/**
 * One jar of a release. Its file is usually another project's task output:
 *
 *     jar("bukkit") {
 *         platform = ReleasePlatform.BUKKIT
 *         file = project(":xbans:bukkit").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
 *         minecraft("1.8.8")          // every Minecraft release from 1.8.8 to xena.ciNewestMinecraft
 *         java = 8
 *     }
 */
abstract class ReleaseJar @Inject constructor(private val name: String) : Named {
    @Input
    override fun getName(): String = name

    @get:Input
    abstract val platform: Property<ReleasePlatform>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val file: RegularFileProperty

    /** The Minecraft versions, listed. Or use [minecraft] for a range. */
    @get:Input
    abstract val gameVersions: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val minecraftFrom: Property<String>

    /** The range's newest version. Defaults to xena.ciNewestMinecraft, the newest CI boots. */
    @get:Input
    @get:Optional
    abstract val minecraftTo: Property<String>

    /**
     * The platform's own versions for Hangar, where they aren't Minecraft's: Velocity's (`3.4`).
     * Empty: Hangar gets the Minecraft versions, as Paper's do.
     */
    @get:Input
    abstract val hangarVersions: ListProperty<String>

    /** Modrinth's loaders, when not the platform's own. */
    @get:Input
    abstract val loaders: ListProperty<String>

    /** The Java version the jar needs, for CurseForge's mod files. */
    @get:Input
    @get:Optional
    abstract val java: Property<Int>

    /** For titles, such as "Fabric 1.21.11". Defaults to the name: `fabric-1.21.11` gives that. */
    @get:Input
    @get:Optional
    abstract val label: Property<String>

    /** Every Minecraft release from [from] to [to] (both included), as Modrinth lists them. */
    fun minecraft(from: String, to: String? = null) {
        minecraftFrom.set(from)
        if (to != null) minecraftTo.set(to)
    }
}
