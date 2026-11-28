plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "The rewrite's platform on Sponge, API 8 (SpongeVanilla 1.16.5) on: one jar for every API line."

dependencies {
    api(project(":core:core-jvm"))
    // The server brings SpongeAPI and, with it, Adventure, Log4j, Guice and geantyref. Compiled
    // against the floor, API 8, so nothing newer is called (xena.sponge-plugin checks the shrunk
    // jars against the newest API too).
    compileOnly(libs.spongeapi)

    testImplementation(libs.spongeapi)
    testImplementation(project(":core:core-testing"))
}
