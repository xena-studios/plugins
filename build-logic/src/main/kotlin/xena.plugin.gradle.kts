import co.xenastudios.gradle.Stores
import co.xenastudios.gradle.VoxelPublish
import io.papermc.hangarpublishplugin.HangarPublishTask
import io.papermc.hangarpublishplugin.PageSyncTask

// A releasable plugin: the core shaded in, a generated paper-plugin.yml, a jar
// size budget, a dev server (runServer) and publishing to Modrinth, Hangar and voxel.shop.
plugins {
    id("xena.java")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.resource-factory-paper-convention")
    id("xyz.jpenilla.run-paper")
    id("com.modrinth.minotaur")
    id("io.papermc.hangar-publish-plugin")
}

val xena = extensions.create<XenaPluginExtension>("xena").apply {
    jarBudgetKb.convention(150)
    folia.convention(true)
    modrinthId.convention(project.name)
}

val libs = the<VersionCatalogsExtension>().named("libs")

// "1.21.11-R0.1-SNAPSHOT" -> "1.21.11"
val minecraftVersion = libs.findVersion("paper").get().requiredVersion.substringBefore("-R0.1")

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.findLibrary("mockbukkit").get())
}

paperPluginYaml {
    apiVersion = minecraftVersion
    authors.add("Xena Studios")
    website = "https://plugins.xenastudios.co/${project.name}/"
    foliaSupported = xena.folia
}

// A plugin with a public API (<plugin>/api, on the xena.api convention) ships it in
// its jar, so other plugins find the API classes in the plugin they depend on.
val apiPath = "${project.path}:api".takeIf { findProject(it) != null }
if (apiPath != null) {
    dependencies {
        implementation(project(apiPath))
    }
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.shadowJar {
    archiveClassifier = ""
    // Each plugin carries its own copy of the core, so two plugins built on
    // different core versions never see each other's classes.
    relocate("co.xenastudios.core", "co.xenastudios.${project.name}.core")
    // bStats insists on being relocated, and into the plugin's own package so
    // each plugin reports on its own.
    relocate("org.bstats", "co.xenastudios.${project.name}.bstats")
    // Drop core classes the plugin never uses. The API stays whole, unrelocated:
    // other plugins use the parts this one doesn't.
    minimize {
        if (apiPath != null) {
            exclude(project(apiPath))
        }
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

val checkJarSize = tasks.register("checkJarSize") {
    group = "verification"
    description = "Fails if the plugin jar is over its size budget."
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    val budget = xena.jarBudgetKb
    inputs.file(jar)
    inputs.property("budget", budget)
    doLast {
        val kb = jar.get().asFile.length() / 1024.0
        if (kb > budget.get()) {
            throw GradleException("%s is %.1f KiB, over its %d KiB budget.".format(jar.get().asFile.name, kb, budget.get()))
        }
        logger.lifecycle("%s: %.1f of %d KiB".format(jar.get().asFile.name, kb, budget.get()))
    }
}

tasks.check {
    dependsOn(checkJarSize)
}

tasks.runServer {
    minecraftVersion(minecraftVersion)
}

// The same for every store: "xPlots 1.0.2", a beta if the version has a "-", the
// release's changelog section, and the Minecraft versions in gradle.properties.
val releaseVersion = provider { project.version.toString() }
val releaseName = paperPluginYaml.name.zip(releaseVersion) { name, version -> "$name $version" }
val prerelease = releaseVersion.map { "-" in it }
val releaseNotes = providers.environmentVariable("CHANGELOG")
val releaseGameVersions = providers.gradleProperty("xena.gameVersions").map { it.split(",") }
// The store page, from modrinth.md. Minotaur drops the modrinth_exclude blocks on its own.
val storePage = providers.fileContents(layout.projectDirectory.file("modrinth.md")).asText

modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = xena.modrinthId
    versionNumber = releaseVersion
    versionName = releaseName
    versionType = prerelease.map { if (it) "beta" else "release" }
    uploadFile.set(tasks.shadowJar)
    gameVersions.addAll(releaseGameVersions)
    loaders.addAll(xena.folia.map { if (it) listOf("paper", "purpur", "folia") else listOf("paper", "purpur") })
    changelog = releaseNotes
    // The project page. modrinthSyncBody publishes <plugin>/modrinth.md; the release
    // workflow runs it, and so does modrinth.yml when the file changes on main.
    syncBodyFrom = storePage
    debugMode = providers.gradleProperty("xena.modrinthDryRun").map { it.toBoolean() }.orElse(false)
}

// A release rerun skips a version Modrinth already has, so it can go on to the other stores.
// Each onlyIf reads only its own locals, which the configuration cache can store.
tasks.named("modrinth") {
    val slug = xena.modrinthId
    val version = releaseVersion
    val token = providers.environmentVariable("MODRINTH_TOKEN")
    val dryRun = modrinth.debugMode
    onlyIf("Modrinth doesn't have this version yet") {
        dryRun.get() || !Stores.modrinthHas(slug.get(), version.get(), token.orNull ?: throw GradleException("MODRINTH_TOKEN isn't set"))
    }
}

// Hangar, PaperMC's plugin site. Only once the plugin has a Hangar project (xena.hangarId).
hangarPublish {
    publications.register("plugin") {
        id = xena.hangarId
        version = releaseVersion
        // Every Hangar project starts with these two channels.
        channel = prerelease.map { if (it) "Snapshot" else "Release" }
        changelog = releaseNotes
        apiKey = providers.environmentVariable("HANGAR_API_TOKEN")
        platforms {
            paper {
                jar = tasks.shadowJar.flatMap { it.archiveFile }
                platformVersions = releaseGameVersions
            }
        }
        pages {
            resourcePage(storePage.map { it.replace(Regex("""<!-- modrinth_exclude\.start -->[\s\S]*?<!-- modrinth_exclude\.end -->\n?"""), "") })
        }
    }
}

tasks.withType<HangarPublishTask>().configureEach {
    val slug = xena.hangarId
    val version = releaseVersion
    val apiKey = providers.environmentVariable("HANGAR_API_TOKEN")
    onlyIf("the plugin has a Hangar project") { slug.isPresent }
    onlyIf("Hangar doesn't have this version yet") {
        !Stores.hangarHas(slug.get(), version.get(), apiKey.orNull ?: throw GradleException("HANGAR_API_TOKEN isn't set"))
    }
}

tasks.withType<PageSyncTask>().configureEach {
    val slug = xena.hangarId
    onlyIf("the plugin has a Hangar project") { slug.isPresent }
}

// voxel.shop. Only once the plugin has a resource there (xena.voxelId).
tasks.register<VoxelPublish>("voxel") {
    group = "publishing"
    description = "Publishes the plugin jar as a new version of its voxel.shop resource."
    resourceId = xena.voxelId
    version = releaseVersion
    title = releaseName
    changelog = releaseNotes
    beta = prerelease
    jar = tasks.shadowJar.flatMap { it.archiveFile }
    apiKey = providers.environmentVariable("VOXEL_API_KEY")
    val resource = xena.voxelId
    onlyIf("the plugin has a voxel.shop resource") { resource.isPresent }
}
