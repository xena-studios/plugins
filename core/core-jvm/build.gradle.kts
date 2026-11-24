plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "The rewrite's core on the JVM, for every JVM platform's adapter."

dependencies {
    api(project(":core:core-common"))
    api(project(":core:core-api"))
}
