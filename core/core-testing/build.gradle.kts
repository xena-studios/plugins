plugins {
    id("xena.kmp")
}

group = "co.xenastudios.core"
description = "A fake platform for tests, on the JVM and wasmWasi: virtual time, files in memory, a recorded log."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:core-common"))
        }
    }
}
