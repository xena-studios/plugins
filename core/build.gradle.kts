plugins {
    id("xena.java")
    `java-test-fixtures`
}

description = "Code every plugin shares, shaded into each one."

dependencies {
    implementation(libs.bstats.bukkit)
    testFixturesImplementation(libs.paper.api)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
}
