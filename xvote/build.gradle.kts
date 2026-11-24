import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.plugin")
}

version = "1.0.1"
description = "Votes in. Rewards out."

dependencies {
    // Optional, and only touched when installed. Vault is reached through the core's hook.
    compileOnly(libs.placeholderapi)
    testRuntimeOnly(libs.sqlite.jdbc)
    // For MySqlTest, against a real MySQL or MariaDB when XR_MYSQL is set.
    testRuntimeOnly(libs.mysql.connector)
}

paperPluginYaml {
    name = "xVote"
    main = "co.xenastudios.xvote.XVote"
    dependencies {
        // Money rewards are paid through Vault's economy.
        server("Vault", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
        server("PlaceholderAPI", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
    }
}

xena {
    // hangar.papermc.io/xena-studios/xVote
    hangarId = "xVote"
    // voxel.shop/product/10713
    voxelId = 10713
    // The shared core and bStats (about 75 KiB), plus the Votifier receiver (both
    // protocols, keys, tokens, the NuVotifier import), the vote ledger and its network
    // claims, rewards, the vote party, commands, placeholders and the developer API. No
    // library is shaded in: JSON is Paper's Gson. About 319 KiB, with ~30 KiB of room.
    jarBudgetKb = 350
}
