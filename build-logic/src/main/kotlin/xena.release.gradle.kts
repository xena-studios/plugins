import co.xenastudios.gradle.CurseForgeClass
import co.xenastudios.gradle.CurseForgePublish
import co.xenastudios.gradle.HangarPublish
import co.xenastudios.gradle.ModrinthPublish
import co.xenastudios.gradle.ReleaseAssemble
import co.xenastudios.gradle.VoxelReleasePublish

// The rewrite's release (ROADMAP.md, "Build, CI and releases"): one tag per plugin
// (`<plugin>-v<version>`), every jar the plugin ships, every store. Applied to the project
// whose version the tag names:
//
//   xenaRelease {
//       pluginId = "xbans"
//       hangarId = "xBans"; voxelId = 12; curseForgeBukkitId = 123456
//       jar("bukkit") { platform = ReleasePlatform.BUKKIT; file = ...; minecraft("1.8.8"); java = 8 }
//       jar("fabric-1.21.11") { platform = ReleasePlatform.FABRIC; file = ...; gameVersions = listOf("1.21.11"); java = 21 }
//   }
//
//   ./gradlew -q releaseInfo               "<plugin id> <project path> <version> <release folder>" for each releasable project
//   ./gradlew <path>:releaseAssemble       build/release: the jars, SHA256SUMS, release.json, update posts
//   ./gradlew <path>:releasePublish        every store, from build/release (release.yml runs each store alone)
//   ./gradlew <path>:release -Pxena.release.dryRun=true
//                                          both, sending nothing: each store prints what it would send
//
// Tokens come from the environment, as release.yml sets them: MODRINTH_TOKEN,
// HANGAR_API_TOKEN, VOXEL_API_KEY, CURSEFORGE_TOKEN. The store tasks read the folder
// releaseAssemble wrote, not the build, so CI publishes exactly the jars it checksummed and
// attested.
val release = extensions.create<XenaReleaseExtension>("xenaRelease").apply {
    pluginId.convention(project.name)
    displayName.convention(pluginId)
    folia.convention(true)
    modrinthId.convention(pluginId)
}

val isDryRun = providers.gradleProperty("xena.release.dryRun").map { it.toBoolean() }.orElse(false)
val releaseOutput = layout.buildDirectory.dir("release")

tasks.register("releaseInfo") {
    group = "publishing"
    description = "Prints the plugin id this project releases as, its path, its version and its release folder."
    val id = release.pluginId
    val path = project.path
    val version = project.version.toString()
    val root = rootDir
    val dir = releaseOutput.map { it.asFile.relativeTo(root).invariantSeparatorsPath }
    doLast { println("${id.get()} $path $version ${dir.get()}") }
}

val releaseAssemble = tasks.register<ReleaseAssemble>("releaseAssemble") {
    group = "publishing"
    description = "Gathers every jar of the release, with checksums, release.json and the update posts."
    pluginId = release.pluginId
    version = project.version.toString()
    displayName = release.displayName
    folia = release.folia
    // <project>/CHANGELOG.md, with a "## <version>" section.
    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    if (changelog.asFile.isFile) changelogFile = changelog
    dryRun = isDryRun
    catalog = providers.gradleProperty("xena.release.catalog").orElse(co.xenastudios.gradle.Release.CATALOG_URL)
    newestMinecraft = providers.gradleProperty("xena.ciNewestMinecraft")
    projects = provider {
        mapOf(
            "modrinth" to release.modrinthId.orNull,
            "hangar" to release.hangarId.orNull,
            "voxel" to release.voxelId.orNull,
            "curseforge-bukkit" to release.curseForgeBukkitId.orNull,
            "curseforge-mods" to release.curseForgeModsId.orNull,
            "curseforge-hytale" to release.curseForgeHytaleId.orNull,
        ).filterValues { it != null }.mapValues { it.value.toString() }
    }
    jars += release.jars
    outputDir = releaseOutput
}

val stores = listOf(
    tasks.register<ModrinthPublish>("releaseModrinth") {
        projectSlug = release.modrinthId
        token = providers.environmentVariable("MODRINTH_TOKEN")
    },
    tasks.register<HangarPublish>("releaseHangar") {
        projectSlug = release.hangarId
        apiKey = providers.environmentVariable("HANGAR_API_TOKEN")
    },
    tasks.register<VoxelReleasePublish>("releaseVoxel") {
        resourceId = release.voxelId
        apiKey = providers.environmentVariable("VOXEL_API_KEY")
    },
) + mapOf(
    CurseForgeClass.BUKKIT to release.curseForgeBukkitId,
    CurseForgeClass.MODS to release.curseForgeModsId,
    CurseForgeClass.HYTALE to release.curseForgeHytaleId,
).map { (cls, id) ->
    tasks.register<CurseForgePublish>("releaseCurseForge" + cls.name.lowercase().replaceFirstChar { it.uppercase() }) {
        projectClass = cls
        projectId = id
        token = providers.environmentVariable("CURSEFORGE_TOKEN")
    }
}

stores.forEach { store ->
    store.configure {
        group = "publishing"
        description = "Publishes the release in build/release to one store."
        mustRunAfter(releaseAssemble)
    }
}
tasks.withType<ModrinthPublish>().configureEach { releaseDir = releaseOutput; dryRun = isDryRun }
tasks.withType<HangarPublish>().configureEach { releaseDir = releaseOutput; dryRun = isDryRun }
tasks.withType<VoxelReleasePublish>().configureEach { releaseDir = releaseOutput; dryRun = isDryRun }
tasks.withType<CurseForgePublish>().configureEach { releaseDir = releaseOutput; dryRun = isDryRun }

val releasePublish = tasks.register("releasePublish") {
    group = "publishing"
    description = "Publishes the release in build/release to every store (-Pxena.release.dryRun=true prints instead)."
    dependsOn(stores)
}

tasks.register("release") {
    group = "publishing"
    description = "Assembles the release and publishes it everywhere (-Pxena.release.dryRun=true prints instead)."
    dependsOn(releaseAssemble, releasePublish)
}
