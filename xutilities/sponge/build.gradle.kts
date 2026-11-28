// xUtilities as a Sponge plugin, API 8 (SpongeVanilla 1.16.5) on, one jar for every API line:
// packaged as every Xena Sponge plugin is (xena.sponge-plugin), with the stdlib, the YAML parser
// and its core inside, relocated to co.xenastudios.xutilities and shrunk by R8 against its line in
// gradle/jar-budgets.txt.
plugins {
    id("xena.sponge-plugin")
}

// The plugin's version, the same as the Bukkit jar's (xutilities/bukkit/build.gradle.kts).
version = "3.0.0"
description = "xUtilities on Sponge, API 8 on."

xenaSpongePlugin {
    id = "xutilities"
}

dependencies {
    implementation(project(":xutilities:common"))
}

tasks.shadowJar {
    archiveFileName = "xUtilities-Sponge.jar"
}
