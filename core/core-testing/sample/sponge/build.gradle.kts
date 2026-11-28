// xSample as a Sponge plugin on platform-sponge, never released: what CI boots on SpongeVanilla
// at API 8 (1.16.5) and the newest (scripts/boot-sponge.sh). Packaged as every Xena Sponge plugin
// is (xena.sponge-plugin): the stdlib, the YAML parser and the core inside, relocated to
// xSample's package and shrunk by R8 against the jar's line in gradle/jar-budgets.txt.
plugins {
    id("xena.sponge-plugin")
}

version = "1.0.0"
description = "xSample as a Sponge plugin, on platform-sponge: boot-tested on SpongeVanilla. Never released."

xenaSpongePlugin {
    id = "xsample"
}

dependencies {
    implementation(project(":core:core-testing:sample"))
    // The JDBC drivers the core fetches when a database needs one (SpongePlatform.drivers).
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
}

tasks.shadowJar {
    archiveFileName = "XSample-Sponge.jar"
}
