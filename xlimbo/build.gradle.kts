import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml

plugins {
    id("xena.plugin")
}

version = "2.1.0"
description = "Never drop a player again."

paperPluginYaml {
    name = "xLimbo"
    main = "co.xenastudios.xlimbo.XLimbo"
    // Before the worlds load, so a world in bukkit.yml can use xLimbo's generator.
    load = BukkitPluginYaml.PluginLoadOrder.STARTUP
}

xena {
    // hangar.papermc.io/xena-studios/xLimbo
    hangarId = "xLimbo"
    // voxel.shop/product/10709
    voxelId = 10709
    // Folia can't create worlds, and a limbo has nothing to spread across regions.
    folia = false
    // The shared core and bStats (about 75 KiB), plus the world, the return queue with
    // its status ping, and the protections. About 184 KiB, with ~35 KiB of room.
    jarBudgetKb = 220
}
