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
// against Paper's API, never shipped with it, to Java 8 class files like the rest of the jar:
// Spigot's Commodore (1.13 on) rewrites a plugin's classes with the ASM of its day, and 1.13.2's
// can't read Java 17 class files, so a newer class file there is a logged error even when
// nothing uses it. Main's classes don't see it, nor does the startup list (R8Shrink), so a server
// without Paper's API never loads it.
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
    // Paper's API is Java 21 class files, which --release 8 can't read, so -source/-target
    // against the build's JDK; the class uses nothing of Java's newer than 8.
    options.release.unset()
    sourceCompatibility = "8"
    targetCompatibility = "8"
    options.compilerArgs.remove("-Werror")
    options.compilerArgs.add("-Xlint:-options")
}
// The proxies' shims, each against its proxy's API in a source set of its own, as Paper's loader
// is: VelocityBootstrap to Java 21 class files (Velocity's API is Java 21, which it needs from
// 3.4), BungeeBootstrap to Java 8 (BungeeCord's API is Java 8 class files, though BungeeCord
// itself now needs 17). Main's classes don't see them, so a server without the proxy's API never
// loads them. Each is a feature of its own (its own jar), which that proxy's platform and plugins
// ask for: `project(":core:core-loader") { capabilities { requireFeature("velocity") } }`.
val velocity = sourceSets.create("velocity") {
    compileClasspath += sourceSets.main.get().output
}
configurations.named(velocity.compileClasspathConfigurationName) {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
val bungee = sourceSets.create("bungee") {
    compileClasspath += sourceSets.main.get().output
}
java {
    registerFeature("velocity") { usingSourceSet(velocity) }
    registerFeature("bungee") { usingSourceSet(bungee) }
}
dependencies {
    // Each feature's jar needs the loader's main classes beside it.
    "velocityApi"(project(":core:core-loader"))
    "bungeeApi"(project(":core:core-loader"))
    "velocityCompileOnly"(libs.velocity.api)
    "bungeeCompileOnly"(libs.bungeecord.api)
}
tasks.named<JavaCompile>("compileVelocityJava") {
    options.release.set(21)
}
tasks.jar {
    from(paper.output)
}
