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
    // migration, the theme, the update checker), bStats, and 1.x's messages so
    // untouched ones get the new look. About 151 KiB; the budget leaves ~50 KiB of
    // room, so it catches accidents (a library shaded in), not normal growth.
    jarBudgetKb = 200
}
