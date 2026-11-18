import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.plugin")
}

version = "1.0.1"
description = "Rentable plots, set up in a minute."

dependencies {
    // All optional, and only touched when installed. Vault is reached through the core's hook.
    // Only the API jars: their own dependencies pin a Guava that clashes with newer Paper's.
    // 7.0.14 is the newest WorldGuard built for Java 21; its API is the same as later 7.0s.
    for (api in listOf(libs.worldguard.bukkit, libs.worldguard.core, libs.worldedit.bukkit, libs.worldedit.core)) {
        compileOnly(api) { isTransitive = false }
    }
    compileOnly(libs.placeholderapi)
    testRuntimeOnly(libs.sqlite.jdbc)
}

paperPluginYaml {
    name = "xPlots"
    main = "co.xenastudios.xplots.XPlots"
    dependencies {
        // Rent is paid through Vault's economy, unless the config says to use an item.
        server("Vault", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
        // One region per field, so a world-wide build deny doesn't block plots.
        server("WorldGuard", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
        server("PlaceholderAPI", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
    }
}

xena {
    // Paper only for 1.0: restoring plots and editing signs across Folia's regions adds
    // risk with little gain for box servers.
    folia = false
    // The shared core and bStats (about 75 KiB), plus fields and their grid, protection,
    // renting, menus, signs, snapshots and the database. About 280 KiB, with ~50 KiB of room.
    jarBudgetKb = 330
}
