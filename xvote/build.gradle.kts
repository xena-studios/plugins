import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.plugin")
}

version = "1.0.0"
description = "Vote rewards, with a built-in Votifier."

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
    // The shared core and bStats (about 75 KiB), plus the Votifier receiver (both
    // protocols, keys, tokens, the NuVotifier import), the vote ledger and its network
    // claims, rewards, the vote party, commands, placeholders and the developer API. No
    // library is shaded in: JSON is Paper's Gson. About 300 KiB, with ~50 KiB of room.
    jarBudgetKb = 350
}
