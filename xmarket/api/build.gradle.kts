plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xMarket's developer API: prices from memory, purchases and sales with xMarket's rules, price modifiers and providers, and the shop's events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Players and events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
