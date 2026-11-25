plugins {
    id("xena.java8")
}

group = "co.xenastudios.core"
description = "The rewrite's library loader and the Java shims that run before Kotlin can: shaded and relocated into each plugin."

dependencies {
    // The oldest Bukkit API the family runs on, so the shim uses nothing newer.
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
}

// Paper's plugin loader (paper-plugin.yml's `loader`), which hands Paper the plugin's libraries
// from the family's cache instead of plugin.yml's `libraries:` (PaperLibraryLoader). Compiled
// against Paper's API, never shipped with it, to Java 17 class files: Paper plugins exist from
// 1.19.3, which ran on Java 17. Main's classes don't see it, so a server without Paper's API
// never loads it.
val paper = sourceSets.create("paper") {
    compileClasspath += sourceSets.main.get().output
}
configurations.named(paper.compileClasspathConfigurationName) {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
dependencies {
    "paperCompileOnly"(libs.paper.api)
}
tasks.named<JavaCompile>("compilePaperJava") {
    // Paper's API is Java 21 class files, which --release 17 can't read, so -source/-target.
    options.release.unset()
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.compilerArgs.remove("-Werror")
}
tasks.jar {
    from(paper.output)
}
