plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xPlots' developer API: fields and plots from memory, renting, members and rent behind cancellable events, price modifiers, access rules and region backends, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players, places and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
