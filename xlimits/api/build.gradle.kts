plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xLimits' developer API: counts and limits per chunk, area limits from other plugins, and the events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
