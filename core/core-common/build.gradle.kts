plugins {
    id("xena.kmp")
}

group = "co.xenastudios.core"
description = "The rewrite's shared logic, for the JVM and wasmWasi: nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Player and world references, locations and the event bus's API.
            api(project(":core:core-api"))
        }
        commonTest.dependencies {
            // The fake platform, which is built on this module: Gradle builds this module's
            // main code, then core-testing, then these tests.
            implementation(project(":core:core-testing"))
        }
    }
}
