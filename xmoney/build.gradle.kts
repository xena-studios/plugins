import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.plugin")
}

// The name players and owners see is set here and in XMoney.NAME.
val pluginName = "xMoney"

version = "1.0.0"
description = "The economy, and Vault, in one plugin."

dependencies {
    // Shipped inside the jar, unrelocated and unmodified: plugins written for Vault or
    // VaultUnlocked compile against these classes at net.milkbowl.vault and .vault2, and
    // find them in xMoney, which stands in for Vault. LGPL-3.0: see META-INF/licenses.
    implementation(libs.vaultunlocked.api) { isTransitive = false }
    compileOnly(libs.placeholderapi)
    testImplementation(libs.placeholderapi)
    testRuntimeOnly(libs.sqlite.jdbc)
    // For MySqlTest, against a real MySQL or MariaDB when XR_MYSQL is set.
    testRuntimeOnly(libs.mysql.connector)
}

paperPluginYaml {
    name = pluginName
    main = "co.xenastudios.xmoney.XMoney"
    // Plugins that depend on Vault load, and find Vault's API, in xMoney. Paper then
    // refuses a real Vault or VaultUnlocked jar beside it (DECISIONS.md).
    provides.add("Vault")
    dependencies {
        server("PlaceholderAPI", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
    }
}

tasks.test {
    // KillTest runs a ledger in a JVM of its own, so it can kill it mid-write.
    val classpath = sourceSets.test.get().runtimeClasspath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dxmoney.testClasspath=" + classpath.asPath) })
}

tasks.shadowJar {
    // Vault's API stays whole: other plugins use the parts xMoney doesn't (permissions, chat).
    minimize {
        exclude(dependency("net.milkbowl.vault:.*:.*"))
    }
}

xena {
    // hangar.papermc.io/xena-studios/xMoney
    hangarId = "xMoney"
    // voxel.shop/product/10715
    voxelId = 10715
    // The shared core and bStats are about 120 KiB here (with the database layer, change
    // feed, profiles and chat pages) and Vault's API 35; the rest is the ledger and its
    // writer, both Vault economies, commands, imports, placeholders and the API. About
    // 369 KiB, with ~30 KiB of room.
    jarBudgetKb = 400
}
