plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "zChat's developer API: mutes, hidden players, placeholders, broadcasts and chat's events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
