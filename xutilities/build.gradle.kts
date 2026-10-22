plugins {
    id("xena.plugin")
}

version = "2.0.0"
description = "Every command you need. None you don't."

paperPluginYaml {
    name = "xUtilities"
    main = "co.xenastudios.xutilities.XUtilities"
}

xena {
    // 1.0.1 was 100.5 KiB. 2.0 adds the shared core (reload for everything, config
    // migration, the update checker) and bStats.
    jarBudgetKb = 130
}
