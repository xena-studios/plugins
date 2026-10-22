// A releasable plugin: the core shaded in, a generated paper-plugin.yml, a jar
// size budget, a dev server (runServer) and Modrinth publishing.
plugins {
    id("xena.java")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.resource-factory-paper-convention")
    id("xyz.jpenilla.run-paper")
    id("com.modrinth.minotaur")
}

val xena = extensions.create<XenaPluginExtension>("xena").apply {
    jarBudgetKb.convention(100)
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
    website = "https://docs.xenastudios.co/${project.name}/"
    foliaSupported = xena.folia
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
    // Drop core classes the plugin never uses.
    minimize()
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

modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = xena.modrinthId
    versionNumber = provider { project.version.toString() }
    // "xUtilities 2.0.0", as the plugin names itself.
    versionName = paperPluginYaml.name.map { "$it ${project.version}" }
    // "2.0.0-beta.1" is a beta; "2.0.0" a release.
    versionType = provider { if ("-" in project.version.toString()) "beta" else "release" }
    uploadFile.set(tasks.shadowJar)
    gameVersions.addAll(providers.gradleProperty("xena.gameVersions").map { it.split(",") })
    loaders.addAll(xena.folia.map { if (it) listOf("paper", "purpur", "folia") else listOf("paper", "purpur") })
    changelog = providers.environmentVariable("CHANGELOG")
}
