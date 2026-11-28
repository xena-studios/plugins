plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "The rewrite's platform on Bukkit, Spigot, Paper and Folia, 1.8.8 to latest, with each server's path picked once at startup."

// What only some servers have (Folia's schedulers, Paper's Adventure, Bukkit's 1.12+ locale
// API), compiled against Paper's API, while the rest is compiled against Spigot 1.8.8's, so
// nothing in main can call what the oldest server lacks. Still Java 8 class files: Paper
// 1.16.5 has Adventure and runs on Java 8. Main uses these classes directly, only once a
// class check at startup has found what they call, and the JVM loads a class only when it's
// first used, so a server without Paper's API never loads them.
val modern = sourceSets.create("modern")
// Main sees its internal declarations (as a module's tests do), without its compile class path:
// associateWith would bring Paper's API along.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name in setOf("compileKotlin", "compileTestKotlin") }.configureEach {
    friendPaths.from(modern.output.classesDirs)
}
// The world hooks (block, container and entity changes; DECISIONS.md, "The world hooks"),
// compiled against Spigot 1.13.2's API: block data starts at 1.13, the floor of every plugin
// that asks for them. Built on main (it sees main's classes, main doesn't see it): a plugin
// reaches it through BukkitWorld.create, which refuses a server before 1.13, so no older
// server ever loads it.
val world = sourceSets.create("world")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name == "compileWorldKotlin" }.configureEach {
    friendPaths.from(sourceSets.main.get().output.classesDirs, modern.output.classesDirs)
}
// Paper's API says it needs Java 21 to run, which is true of the server, not of these classes.
configurations.named(modern.compileClasspathConfigurationName) {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

dependencies {
    api(project(":core:core-jvm"))
    // The oldest Bukkit the family runs on, and the chat API Spigot 1.8.8 bundles: main
    // compiles against nothing newer. Servers bring their own.
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    compileOnly(libs.bungeecord.chat)
    // bStats, which each plugin's jar carries relocated into its own package (BukkitUpdates):
    // bStats refuses to run under its own.
    compileOnly(libs.bstats.bukkit)
    "modernImplementation"(project(":core:core-jvm"))
    "modernCompileOnly"(libs.paper.api)
    implementation(modern.output)
    "worldImplementation"(project(":core:core-jvm"))
    "worldCompileOnly"(libs.spigot.api.world) { isTransitive = false }
    "worldCompileOnly"(sourceSets.main.get().output)
    "worldCompileOnly"(modern.output)

    // The adapter's logic runs against Spigot 1.8.8's API and what it needs at runtime, with
    // fakes for the server. Its own chat API (1.8-SNAPSHOT) is in no public repository.
    testImplementation(libs.spigot.api.floor) { exclude(group = "net.md-5") }
    testImplementation(libs.bungeecord.chat)
    testImplementation(libs.adventure.api)
    testImplementation(project(":core:core-testing"))
    testImplementation(world.output)
}

tasks.jar {
    from(modern.output)
    from(world.output)
}

// A plugin compiles against the world hooks as against main: their classes go out with main's,
// both in the jar (above) and as the classes a consumer compiles against. Main itself never
// sees them, so there's no cycle.
for (elements in listOf("apiElements", "runtimeElements")) {
    configurations.named(elements) {
        outgoing.variants.matching { it.name == "classes" }.configureEach {
            for (dir in world.output.classesDirs) artifact(dir) { builtBy(world.output) }
        }
    }
}
