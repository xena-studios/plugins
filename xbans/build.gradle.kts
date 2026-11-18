plugins {
    id("xena.plugin")
}

version = "1.0.0"
description = "Bans, mutes and warnings. Nothing slips through."

paperPluginYaml {
    name = "xBans"
    main = "co.xenastudios.xbans.XBans"
}

xena {
    // The shared core and bStats are about 75 KiB; the rest is xBans' own: about 25
    // commands and their flags, the join and mute checks, history pages, alts and IPs,
    // templates, staff limits, network mode, the importers, and its API (shipped whole,
    // for other plugins). No database driver is shaded in: Paper and Folia ship SQLite's
    // and MySQL's.
    jarBudgetKb = 400
}

dependencies {
    testRuntimeOnly(libs.sqlite.jdbc)
    // For MySqlTest, against a real MySQL or MariaDB when XR_MYSQL is set.
    testRuntimeOnly(libs.mysql.connector)
}
