plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xLimbo's developer API: the main server's status, the return queue, sending players back and into limbo, the add-on hooks and the events."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
