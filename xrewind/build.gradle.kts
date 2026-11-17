plugins {
    id("xena.plugin")
}

version = "1.0.1"
description = "Every change, logged. Any of it, undone."

paperPluginYaml {
    name = "xRewind"
    main = "co.xenastudios.xrewind.XRewind"
}

xena {
    // The shared core and bStats are about 75 KiB; the rest is xRewind's own code: about
    // 25 listeners, the writer, lookups across five tables, and rollbacks with contents
    // and undo. No database driver is shaded in: Paper and Folia ship SQLite's and
    // MySQL's. About 250 KiB with purges and the CoreProtect import still to come.
    jarBudgetKb = 350
}

dependencies {
    testRuntimeOnly(libs.sqlite.jdbc)
    // For MySqlTest, against a real MySQL or MariaDB when XR_MYSQL is set.
    testRuntimeOnly(libs.mysql.connector)
}
