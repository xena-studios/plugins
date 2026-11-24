plugins {
    id("xena.java8")
}

group = "co.xenastudios.core"
description = "The rewrite's library loader and the Java shims that run before Kotlin can: shaded and relocated into each plugin."

dependencies {
    // The oldest Bukkit API the family runs on, so the shim uses nothing newer.
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
}
