plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xVote's developer API: totals, leaderboards, the vote party, reward types and the vote events, the same on every platform and on the proxies."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
