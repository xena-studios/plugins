import co.xenastudios.gradle.R8Shrink
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// A Xena plugin's NeoForge mod, one jar per Minecraft line (core/platform-neoforge's Stonecutter
// nodes), packaged as every Xena mod is (xena.fabric-mod; decisions/2026-11-28-neoforge-and-forge.md):
//
//     plugins {
//         id("net.neoforged.moddev")
//         kotlin("jvm")
//         id("xena.neoforge-mod")
//     }
//     xenaNeoForgeMod { id = "xutilities" }
//     dependencies { "bundle"(...) }
//
// 1. `jar`: the mod's classes and everything in `bundle` (the adapter, the core, the plugin's
//    shared code) as classes. The stdlib (Kotlin for Forge's) and the YAML parser (downloaded,
//    xena.fetched-libraries) stay out.
// 2. `relocateJar`: the core moved to `co.xenastudios.<id>.core`. NeoForge loads every mod as a
//    module of one layer, where two jars may not share a package, so two Xena mods carrying the
//    core under its own names wouldn't even start together.
// 3. `shrinkJar` (xena.r8): R8 on the relocated jar, against Minecraft's and NeoForge's jar, the
//    stdlib and the downloaded libraries, renamed classes in the package `<id>`, with the jar's
//    line in gradle/jar-budgets.txt. The class NeoForge makes by its `@Mod` annotation, and the
//    classes it calls by theirs, keep their names.
// 4. On Forge 1.20.1 (ModDevGradle's legacy plugin, net.neoforged.moddev.legacyforge), its
//    `reobfJar`: the shrunk jar to SRG names, which Forge runs. NeoForge runs Mojang's names,
//    which the shrunk jar already has, so there nothing is remapped.
// 5. `collect`: the jar that ships, into the build's build/libs, for the boot scripts.
//
// The jar carries the descriptor of its loader: META-INF/neoforge.mods.toml on NeoForge,
// META-INF/mods.toml on Forge, from a mod's resources, which have both.
plugins {
    java
    id("xena.r8")
    id("xena.fetched-libraries")
}

val mod = extensions.create<XenaNeoForgeModExtension>("xenaNeoForgeMod")

// R8, only on Google's repository. ModDevGradle adds repositories to its projects too.
repositories {
    google {
        content { includeModule("com.android.tools", "r8") }
    }
}

// What goes inside the mod's jar, as classes. A plugin carries its own core, as on Bukkit and
// Fabric: NeoForge's jar-in-jar would keep only the newest copy among every mod that has one.
val bundle = configurations.dependencyScope("bundle")
val bundleClasspath = configurations.resolvable("bundleClasspath") {
    extendsFrom(bundle.get())
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "it.krzeminski")
    exclude(group = "com.squareup.okio")
    exclude(group = "net.thauvin.erik.urlencoder")
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

val jar = tasks.named<Jar>("jar") {
    from(bundleClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
    // The mapped collection doesn't carry the tasks that build its jars (the adapter's), so they're named.
    dependsOn(bundleClasspath)
    // Each library's own manifest and module files; ours win.
    exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val relocateJar = tasks.register<ShadowJar>("relocateJar") {
    group = "build"
    description = "The mod's jar with its own copy of the core, relocated to its plugin's package."
    from(jar.map { zipTree(it.archiveFile) })
    archiveFileName.set(jar.flatMap { it.archiveFileName })
    destinationDirectory.set(layout.buildDirectory.dir("relocated"))
    // Only our core moves: Minecraft's, NeoForge's, Kotlin's and the YAML parser's names stay.
    relocate("co.xenastudios.core", "co.xenastudios.${mod.id.get()}.core")
}

val neoForgeRules = tasks.register("neoForgeR8Rules") {
    description = "Writes the keep rules for what NeoForge finds by annotation."
    val out = layout.buildDirectory.file("xena/neoforge.pro")
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(
            """
            |# NeoForge's entry points (xena.neoforge-mod)
            |-keep @net.neoforged.fml.common.Mod class * { <init>(...); }
            |-keep @net.neoforged.fml.common.EventBusSubscriber class * { *; }
            |-keep @net.minecraftforge.fml.common.Mod class * { <init>(...); }
            |-keep @net.minecraftforge.fml.common.Mod${'$'}EventBusSubscriber class * { *; }
            |""".trimMargin(),
        )
    }
}

val fetchedJars = configurations.named("fetchedJars")
the<XenaR8Extension>().apply {
    inputJar.setFrom(relocateJar)
    packageName.convention(mod.id)
    // What the server has beside the jar: Minecraft and NeoForge, the stdlib, and the libraries
    // downloaded at runtime. Not what's bundled.
    libraries.setFrom(configurations.named("compileClasspath").map { it.minus(bundleClasspath.get()) }, fetchedJars)
    // NeoForge makes the mod's class by its @Mod annotation, and finds the classes it calls
    // listeners on by @EventBusSubscriber: neither is named in a file R8 reads. The project's
    // own r8/*.pro too, as xena.r8 would read them.
    rules.setFrom(neoForgeRules, fileTree("r8") { include("*.pro") })
}

val shrinkJar = tasks.named<R8Shrink>("shrinkJar")

// Forge 1.20.1 or NeoForge: decided by the ModDevGradle plugin the mod applies.
val forge = providers.provider { plugins.hasPlugin("net.neoforged.moddev.legacyforge") }

tasks.named<ProcessResources>("processResources") {
    val other = forge.map { if (it) "META-INF/neoforge.mods.toml" else "META-INF/mods.toml" }
    exclude { it.path == other.get() }
}

// On Forge, the shrunk jar is the one ModDevGradle remaps to SRG names (its task type isn't on
// build-logic's class path, so its input is set by name), after ModDevGradle's own setting,
// which it makes once the project is evaluated.
plugins.withId("net.neoforged.moddev.legacyforge") {
    afterEvaluate {
        tasks.named("reobfJar") {
            @Suppress("UNCHECKED_CAST")
            (property("input") as RegularFileProperty).set(shrinkJar.flatMap { it.outputJar })
        }
    }
}

tasks.register<Copy>("collect") {
    group = "build"
    description = "Copies this version's mod jar to the build's build/libs."
    if ("reobfJar" in project.tasks.names) {
        @Suppress("UNCHECKED_CAST")
        from(project.tasks.named("reobfJar").flatMap { it.property("archiveFile") as Provider<RegularFile> })
    } else {
        from(shrinkJar.flatMap { it.outputJar })
    }
    into(isolated.rootProject.projectDirectory.dir("build/libs"))
}
