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
    // The shared core and bStats are about 75 KiB. No database driver is shaded in:
    // Paper and Folia ship SQLite's and MySQL's.
    jarBudgetKb = 150
}

dependencies {
    testRuntimeOnly(libs.sqlite.jdbc)
}
