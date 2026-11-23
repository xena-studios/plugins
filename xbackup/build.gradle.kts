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
    // hangar.papermc.io/xena-studios/xBackup
    hangarId = "xBackup"
    // voxel.shop/product/10717
    voxelId = 10717
    // The shared core and bStats (about 75 KiB), plus the store engine, the backup scanner, restores
    // and the command-line restore, and the S3, WebDAV, SFTP, FTP, Google Drive, OneDrive and Dropbox
    // remotes (the SFTP and FTP libraries are downloaded when used, never shaded). About 482 KiB,
    // with ~50 KiB of room.
    jarBudgetKb = 530
}

tasks.shadowJar {
    // `java -jar xBackup.jar restore ...`: the restore that works with the server stopped.
    manifest {
        attributes("Main-Class" to "co.xenastudios.xbackup.cli.Main")
    }
}
