plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "The rewrite's platform on Velocity, 3.4 on and 4.x: a plugin's proxy side."

// Velocity's API is Java 21 class files; this module stays Java 8 bytecode, as the core is, which
// Velocity's Java 21 runs.
configurations.named("compileClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
configurations.named("testCompileClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
configurations.named("testRuntimeClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

dependencies {
    api(project(":core:core-jvm"))
    // The shim a plugin's main class extends, which hands this module the proxy.
    api(project(":core:core-loader")) { capabilities { requireFeature("velocity") } }
    // The proxy brings its own API, Adventure, Brigadier and SLF4J.
    compileOnly(libs.velocity.api)
    // bStats (VelocityPlatform.metrics), which each plugin's jar carries relocated.
    compileOnly(libs.bstats.velocity)

    testImplementation(libs.velocity.api)
    testImplementation(project(":core:core-testing"))
}
