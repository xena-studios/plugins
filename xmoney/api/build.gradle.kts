plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xMoney's developer API: balances, payments with their causes, the top list, history and the economy's events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
