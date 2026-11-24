import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xUtilities as a server-side Fabric mod, one jar per Minecraft line: a branch of
// core/platform-fabric's own build (its settings.gradle.kts puts this folder there), so it's
// built with `./gradlew -p core/platform-fabric :xutilities:<version>:collect`. Each version's
// node runs this script, with `sc.current` the version.
//
// The sandbox's jar (R8 on Mojang's names, then Loom's remap) with xTwin's last step: the core
// relocated to xUtilities' own package by Shadow after the remap, so two Xena mods in Fabric's
// one class loader each have their own core (DECISIONS.md, "The boot matrix").
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    id("com.gradleup.shadow") apply false
    id("xena.fetched-libraries")
    id("xena.r8")
}

// The plugin's version, the same as the Bukkit jar's (xutilities/bukkit/build.gradle.kts).
version = "3.0.0-alpha.1+${sc.current.version}"
base.archivesName = "xutilities-fabric-unrelocated"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    google {
        content { includeModule("com.android.tools", "r8") }
    }
}

// Inside the mod's jar, as classes: the adapter, the core, xUtilities and its API. The stdlib
// is fabric-language-kotlin's, and the YAML parser is downloaded (fetched, below).
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

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"]}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"]}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${sc.properties["deps.flk"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    modImplementation("me.lucko:fabric-permissions-api:${sc.properties["deps.permissions"]}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    include("me.lucko:fabric-permissions-api:${sc.properties["deps.permissions"]}")

    val adapter = project(":${sc.current.project}", configuration = "namedElements")
    implementation(adapter)
    implementation("co.xenastudios.core:core-jvm")
    implementation("co.xenastudios:xutilities-common")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios:xutilities-common")
    "bundle"("co.xenastudios.core:core-jvm")

    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
}

xenaR8 {
    packageName = "co.xenastudios.xutilities.r8"
    libraries.setFrom(configurations.compileClasspath.map { it.minus(bundleClasspath.get()) }, configurations.named("fetchedJars"))
}

java {
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
}

kotlin {
    explicitApi()
    jvmToolchain(requiredJava.majorVersion.toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(requiredJava.majorVersion)
        allWarningsAsErrors = true
    }
}

loom {
    runConfigs.all {
        runDirectory = file("run")
    }
}

tasks {
    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "minecraft" to sc.properties["mod.mc_compat"],
            "loader" to sc.properties["mod.loader_compat"],
            "flk" to sc.properties["deps.flk"].substringBefore('+'),
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    jar {
        from(bundleClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
        exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // The shrunk jar is the one remapped, below 26.1, and the one relocated on 26.1 and later.
    matching { it.name == "remapJar" }.configureEach {
        (this as net.fabricmc.loom.task.RemapJarTask).inputFile.set(named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar })
    }

    // The mod's jar with the core moved to xUtilities' packages. Minecraft's, Fabric's, Kotlin's
    // and the YAML parser's names stay.
    val relocatedJar = register<ShadowJar>("relocatedJar") {
        group = "build"
        description = "The mod's jar with its own copy of the core, relocated."
        archiveBaseName = "xutilities-fabric"
        archiveVersion = project.version.toString()
        archiveClassifier = ""
        destinationDirectory = layout.buildDirectory.dir("relocated")
        if (sc.current.parsed >= "26.1") {
            dependsOn("shrinkJar")
            from(zipTree(named<co.xenastudios.gradle.R8Shrink>("shrinkJar").flatMap { it.outputJar }))
        } else {
            from(loomx.modJar.map { zipTree(it.archiveFile) })
        }
        relocate("co.xenastudios.core", "co.xenastudios.xutilities.core")
    }

    assemble { dependsOn(relocatedJar) }

    register<Copy>("collect") {
        group = "build"
        description = "Copies this version's mod jar to the build's build/libs."
        from(relocatedJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs"))
    }
}
