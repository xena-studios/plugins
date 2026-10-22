import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.plugin")
}

version = "3.0.0"
description = "Format your chat. Keep it clean."

dependencies {
    compileOnly(libs.placeholderapi)
}

paperPluginYaml {
    name = "zChat"
    main = "co.xenastudios.zchat.ZChat"
    dependencies {
        // Optional: fills in %placeholders% in chat formats when it's installed.
        server("PlaceholderAPI", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
    }
}

xena {
    // The shared core and bStats (about 75 KiB), plus chat formats, the filter,
    // mentions, anti-repeat and private messages. About 159 KiB, with ~50 KiB of room.
    jarBudgetKb = 210
}

tasks.runServer {
    // To try %placeholders% in formats on the dev server.
    downloadPlugins {
        modrinth("placeholderapi", libs.versions.placeholderapi.get())
    }
}
