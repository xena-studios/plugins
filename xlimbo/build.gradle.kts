import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml

plugins {
    id("xena.plugin")
}

version = "2.0.0"
description = "Never drop a player again."

paperPluginYaml {
    name = "xLimbo"
    main = "co.xenastudios.xlimbo.XLimbo"
    // Before the worlds load, so a world in bukkit.yml can use xLimbo's generator.
    load = BukkitPluginYaml.PluginLoadOrder.STARTUP
}

xena {
    // Folia can't create worlds, and a limbo has nothing to spread across regions.
    folia = false
    // The shared core and bStats (about 75 KiB), plus the world, the return queue with
    // its status ping, and the protections. About 165 KiB, with ~50 KiB of room.
    jarBudgetKb = 220
}
