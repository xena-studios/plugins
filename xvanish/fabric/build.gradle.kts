plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios"
version = "0.1.0"
description = "xVanish for Fabric."

// TODO: stubbed on the platform layer, which is being written (M1); its conventions bring
// Loom, the Fabric API and fabric-language-kotlin.
dependencies {
    implementation(project(":xvanish:common"))
    implementation(project(":core:platform-fabric"))
}
