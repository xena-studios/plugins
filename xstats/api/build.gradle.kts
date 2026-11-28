plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xStats' developer API: players' stats, ranks and leaderboards, stats and profile sections of your own, and the stats events, the same on every platform and on the proxy."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
