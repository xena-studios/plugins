import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.plugin")
}

version = "1.0.0"
description = "Player stats, leaderboards and a website."

dependencies {
    // Optional: xStats' own placeholders, and stats sampled from other plugins' placeholders.
    compileOnly(libs.placeholderapi)
    testRuntimeOnly(libs.sqlite.jdbc)
    // For MySqlTest, against a real MySQL or MariaDB when XR_MYSQL is set.
    testRuntimeOnly(libs.mysql.connector)
}

paperPluginYaml {
    name = "xStats"
    main = "co.xenastudios.xstats.XStats"
    dependencies {
        server("PlaceholderAPI", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
    }
}

xena {
    // The shared core and bStats (about 75 KiB), the collector, leaderboards, the web
    // server and its JSON, and the website itself (plain HTML, CSS and JS: about 64 KiB
    // since its redesign in #105, 18 compressed). About 398 KiB, with ~50 KiB of room.
    jarBudgetKb = 450
}

// The website is plain files in web/, with no build step of its own: the jar serves them
// from web/ in its resources, and the same files go in a zip for hosting anywhere else.
val site = layout.projectDirectory.dir("web")

tasks.processResources {
    from(site) {
        into("web")
    }
}

val siteZip = tasks.register<Zip>("siteZip") {
    group = "build"
    description = "The stats website as static files, to host anywhere and point at the API."
    archiveBaseName = "xstats-site"
    from(site)
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

tasks.assemble {
    dependsOn(siteZip)
}

tasks.runServer {
    downloadPlugins {
        modrinth("placeholderapi", libs.versions.placeholderapi.get())
    }
}
