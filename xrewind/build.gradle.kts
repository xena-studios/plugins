plugins {
    id("xena.plugin")
}

version = "1.1.1"
description = "Every change, logged. Any of it, undone."

paperPluginYaml {
    name = "xRewind"
    main = "co.xenastudios.xrewind.XRewind"
}

xena {
    // hangar.papermc.io/xena-studios/xRewind
    hangarId = "xRewind"
    // voxel.shop/product/10710
    voxelId = 10710
    // The shared core and bStats are about 75 KiB; the rest is xRewind's own code: about
    // 25 listeners, the writer, lookups across five tables, rollbacks with contents and
    // undo, purges and the CoreProtect import. No database driver is shaded in: Paper and
    // Folia ship SQLite's and MySQL's. About 326 KiB.
    jarBudgetKb = 350
}

dependencies {
    testRuntimeOnly(libs.sqlite.jdbc)
    // For MySqlTest, against a real MySQL or MariaDB when XR_MYSQL is set.
    testRuntimeOnly(libs.mysql.connector)
}
