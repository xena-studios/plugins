plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
version = "1.0.0"
description = "xTwin: a second plugin on the rewrite's core, never released, for the family test: booted beside xSample, each with its own relocated core, to show they share one board, one player cache and the family's folder."

dependencies {
    // Java 8 bytecode like the core (xena.kotlin-jvm), so it runs from Spigot 1.8.8 to Fabric 26.3.
    api(project(":core:core-common"))
}
