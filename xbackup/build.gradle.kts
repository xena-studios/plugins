plugins {
    id("xena.plugin")
}

version = "1.0.0"
description = "Incremental backups that cost almost nothing, and a restore you can trust."

dependencies {
    testRuntimeOnly(libs.sqlite.jdbc)
}

paperPluginYaml {
    name = "xBackup"
    main = "co.xenastudios.xbackup.XBackup"
    bootstrapper = "co.xenastudios.xbackup.XBackupBootstrap"
}

xena {
    jarBudgetKb = 400
}

tasks.shadowJar {
    // `java -jar xBackup.jar restore ...`: the restore that works with the server stopped.
    manifest {
        attributes("Main-Class" to "co.xenastudios.xbackup.cli.Main")
    }
}
