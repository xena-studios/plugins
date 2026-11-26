plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xTeleport's developer API: teleports with xTeleport's warmup, cooldown, cost and safety check, homes, warps, the spawn, the random teleport search, the add-on seams and the events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players, places and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
