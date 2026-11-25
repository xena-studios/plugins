plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "The rewrite's platform on BungeeCord and Waterfall: a plugin's proxy side, Java 8 bytecode."

dependencies {
    api(project(":core:core-jvm"))
    // The shim a plugin's main class extends.
    api(project(":core:core-loader")) { capabilities { requireFeature("bungee") } }
    // The proxy brings its own API and chat components (BungeeRenderer draws to them).
    compileOnly(libs.bungeecord.api)
    // bStats (BungeePlatform.metrics), which each plugin's jar carries relocated.
    compileOnly(libs.bstats.bungeecord)

    testImplementation(libs.bungeecord.api)
    testImplementation(project(":core:core-testing"))
}
