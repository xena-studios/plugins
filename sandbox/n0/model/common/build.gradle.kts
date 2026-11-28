// N0 spike (#202): the neutral game model NeoSkript's syntax would sit on, in shared code
// (jvm and wasmWasi, so nothing from java.* creeps in). Never released.
plugins {
    id("xena.kmp")
}

group = "co.xenastudios.sandbox"
description = "N0 spike: a neutral game model for NeoSkript, the same calls on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:core-api"))
        }
    }
}
