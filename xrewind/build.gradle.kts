plugins {
    id("xena.plugin")
}

version = "0.1.0"
description = "Every change, logged. Any of it, undone."

paperPluginYaml {
    name = "xRewind"
    main = "co.xenastudios.xrewind.XRewind"
}

xena {
    // The shared core and bStats are about 75 KiB, and logging (the queue, the writer
    // and its listeners) about 65. No database driver is shaded in: Paper and Folia ship
    // SQLite's and MySQL's. About 140 KiB with lookups, rollback and the CoreProtect
    // import still to come, so the budget leaves room for them.
    jarBudgetKb = 250
}

dependencies {
    testRuntimeOnly(libs.sqlite.jdbc)
}
