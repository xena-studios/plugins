plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "The rewrite's core on the JVM, for every JVM platform's adapter."

dependencies {
    api(project(":core:core-common"))
    api(project(":core:core-api"))
    // Reads config files (the config package's JvmYaml).
    implementation(libs.snakeyaml.engine.kmp)
    // The db package's tests run on real drivers: SQLite always, MySQL or MariaDB when XR_MYSQL
    // is set. Never shipped: servers bring their own, or the loader fetches them (DriverLibrary).
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.mysql.connector)
    // The fake platform's clock and log, for the change feed's tests.
    testImplementation(project(":core:core-testing"))
    // The servers' own text, which the text package draws messages to: never shipped.
    compileOnly(libs.adventure.api)
    compileOnly(libs.bungeecord.chat)
    testImplementation(libs.adventure.api)
    testImplementation(libs.bungeecord.chat)
    // Reads the renderer's class files, to keep it to what Spigot 1.8.8's chat API has.
    testImplementation(libs.asm.tree)
}

tasks.test {
    // CI sets it to its MariaDB (plugins.yml); the MySQL tests are skipped without it. Declared
    // here so a change to it runs the tests again rather than reusing the last result.
    environment("XR_MYSQL", System.getenv("XR_MYSQL") ?: "")
}
