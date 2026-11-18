import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.plugin")
}

version = "1.0.0"
description = "Every teleport your server needs."

dependencies {
    // Optional, and only touched when installed. Vault is reached through the core's hook.
    compileOnly(libs.placeholderapi)
    testRuntimeOnly(libs.sqlite.jdbc)
}

paperPluginYaml {
    name = "xTeleport"
    main = "co.xenastudios.xteleport.XTeleport"
    dependencies {
        // Teleport costs, when the config sets any.
        server("Vault", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
        server("PlaceholderAPI", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
    }
}

xena {
    // The shared core and bStats are about 75 KiB, the API about 15; the rest is homes,
    // TPA, warps, spawn, the RTP search, the teleport pipeline and the EssentialsX import.
    // About 280 KiB, with ~50 KiB of room.
    jarBudgetKb = 330
}
