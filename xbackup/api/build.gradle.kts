plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "xBackup's developer API: run backups, read the list and status from memory, add sources, keep a plugin's files still while they're read, add storage, and the backup events, the same on every platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Events, as every Xena plugin's API has them.
            api(project(":core:core-api"))
        }
    }
}
