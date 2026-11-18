plugins {
    id("xena.java")
    `java-test-fixtures`
}

description = "Code every plugin shares, shaded into each one."

dependencies {
    implementation(libs.bstats.bukkit)
    testRuntimeOnly(libs.sqlite.jdbc)
    // For ChangeFeedMySqlTest, against a real MySQL or MariaDB when XR_MYSQL is set.
    testRuntimeOnly(libs.mysql.connector)
    testFixturesImplementation(libs.paper.api)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
}
