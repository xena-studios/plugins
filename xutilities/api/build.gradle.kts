plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xUtilities' developer API: /back spots, which commands are on, and /back's events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players, places and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
