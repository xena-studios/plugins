plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xVanish's developer API: who's vanished, who can see whom, vanishing and showing players, and the events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
