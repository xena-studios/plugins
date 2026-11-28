plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xRewind's developer API: logging changes, lookups, rollbacks and restores (in one region too), filters and the events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
