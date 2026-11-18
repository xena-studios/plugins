plugins {
    id("xena.plugin")
}

version = "1.0.0"
description = "Incremental backups that cost almost nothing, and a restore you can trust."

dependencies {
    // Loaded at runtime only when an SFTP or FTP remote is set up (see remote/Libraries).
    compileOnly(libs.jsch)
    compileOnly(libs.commons.net)
    testImplementation(libs.jsch)
    testImplementation(libs.commons.net)
    testRuntimeOnly(libs.sqlite.jdbc)
}

paperPluginYaml {
    name = "xBackup"
    main = "co.xenastudios.xbackup.XBackup"
    bootstrapper = "co.xenastudios.xbackup.XBackupBootstrap"
}

xena {
    // The shared core and bStats (about 75 KiB), plus the store engine, the backup scanner, restores
    // and the command-line restore, and the S3, WebDAV, SFTP and FTP remotes (their libraries are
    // downloaded when used, never shaded). About 390 KiB.
    jarBudgetKb = 500
}

tasks.shadowJar {
    // `java -jar xBackup.jar restore ...`: the restore that works with the server stopped.
    manifest {
        attributes("Main-Class" to "co.xenastudios.xbackup.cli.Main")
    }
}
