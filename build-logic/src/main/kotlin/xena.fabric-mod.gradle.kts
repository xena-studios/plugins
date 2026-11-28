import co.xenastudios.gradle.R8Shrink
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// A Xena plugin's Fabric mod, one jar per Minecraft line (core/platform-fabric's Stonecutter
// nodes), packaged the same way for every plugin (DECISIONS.md, "Fabric jars relocate their core"):
//
//     plugins {
//         id("dev.kikugie.loom-back-compat")
//         kotlin("jvm")
//         id("xena.fabric-mod")
//     }
//     xenaFabricMod { id = "xutilities" }
//     dependencies { "bundle"(...) }
//
// 1. `jar`: the mod's classes and everything in `bundle` (the adapter, the core, the plugin's
//    shared code) as classes, in Mojang's names. The stdlib (fabric-language-kotlin's) and
//    the YAML parser (downloaded, xena.fetched-libraries) stay out.
// 2. `relocateJar`: the core moved to `co.xenastudios.<id>.core`. Fabric loads every mod in one
//    class loader, so two Xena mods carrying the core under its own names would share one copy
//    (whichever loaded first), as on Bukkit before each plugin relocated its own.
// 3. `shrinkJar` (xena.r8): R8 on the relocated jar, against Minecraft's, Fabric's and the
//    stdlib's named jars and the downloaded libraries, renamed classes in the package `<id>`,
//    with the jar's line in gradle/jar-budgets.txt.
// 4. `remapJar` (Loom, below 26.1): the shrunk jar to intermediary names. From 26.1, Minecraft
//    isn't obfuscated and the shrunk jar ships as it is.
// 5. `collect`: the jar that ships, into the build's build/libs, for the boot scripts.
//
// Relocating first means R8 and Loom both see the final names, and nothing after R8 rewrites
// its output, so the mapping file is the shipped jar's.
plugins {
    java
    id("xena.r8")
    id("xena.fetched-libraries")
}

val mod = extensions.create<XenaFabricModExtension>("xenaFabricMod")
mod.worldHooks.convention(false)

// R8, only on Google's repository. The Fabric build lets projects add repositories (Loom does).
repositories {
    google {
        content { includeModule("com.android.tools", "r8") }
    }
}

// What goes inside the mod's jar, as classes. A plugin carries its own core, as on Bukkit:
// Fabric's jar-in-jar would keep only the newest copy among every mod that has one.
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
    // Each library's own manifest and module files; ours win.
    exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The world hooks' mixin config, as the mod's own (its name and package are the mod's, as the
    // relocation below makes the mixins'), or nothing when the mod doesn't use them.
    val id = mod.id
    val hooks = mod.worldHooks
    inputs.property("worldHooks", hooks)
    eachFile {
        if (path == "xena-world.mixins.json") {
            if (!hooks.get()) {
                exclude()
            } else {
                path = "${id.get()}-world.mixins.json"
                filter { line -> line.replace("\"co.xenastudios.core.", "\"co.xenastudios.${id.get()}.core.") }
            }
        }
    }
}

val relocateJar = tasks.register<ShadowJar>("relocateJar") {
    group = "build"
    description = "The mod's jar with its own copy of the core, relocated to its plugin's package."
    from(jar.map { zipTree(it.archiveFile) })
    archiveFileName.set(jar.flatMap { it.archiveFileName })
    destinationDirectory.set(layout.buildDirectory.dir("relocated"))
    // Only our core moves: Minecraft's, Fabric's, Kotlin's and the YAML parser's names stay.
    relocate("co.xenastudios.core", "co.xenastudios.${mod.id.get()}.core")
}

val fetchedJars = configurations.named("fetchedJars")
the<XenaR8Extension>().apply {
    inputJar.setFrom(relocateJar)
    packageName.convention(mod.id)
    // What the server has beside the jar: Minecraft, Fabric and the stdlib (in Mojang's names,
    // since R8 runs before Loom remaps) and the libraries downloaded at runtime. Not what's bundled.
    libraries.setFrom(configurations.named("compileClasspath").map { it.minus(bundleClasspath.get()) }, fetchedJars)
}

val shrinkJar = tasks.named<R8Shrink>("shrinkJar")

// Below 26.1 the shrunk jar is the one Loom remaps (Loom's own task type isn't on build-logic's
// class path, so its input is set by name).
tasks.matching { it.name == "remapJar" }.configureEach {
    @Suppress("UNCHECKED_CAST")
    (property("inputFile") as RegularFileProperty).set(shrinkJar.flatMap { it.outputJar })
}

tasks.register<Copy>("collect") {
    group = "build"
    description = "Copies this version's mod jar to the build's build/libs."
    if ("remapJar" in project.tasks.names) {
        @Suppress("UNCHECKED_CAST")
        from(project.tasks.named("remapJar").flatMap { it.property("archiveFile") as Provider<RegularFile> })
    } else {
        from(shrinkJar.flatMap { it.outputJar })
    }
    into(isolated.rootProject.projectDirectory.dir("build/libs"))
}
