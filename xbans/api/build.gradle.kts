plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xBans' developer API: punishments, their history, the staff layer and the events, the same on every platform and on the proxies."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
