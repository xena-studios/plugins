import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xUtilities as a NeoForge mod, one jar per Minecraft line (core/platform-neoforge's nodes for
// the same versions): a branch of that build, in the plugin's own folder (ROADMAP.md,
// Architecture). Booted by scripts/boot-neoforge.sh with boot-checks.txt beside it.
plugins {
    id("net.neoforged.moddev")
    kotlin("jvm")
    // How every Xena mod is packaged (build-logic): the core bundled and relocated to xUtilities'
    // package, shrunk by R8 against the jar's line in gradle/jar-budgets.txt; the YAML parser
    // downloaded at startup (NeoForgeLibraries), pinned in gradle/library-pins.txt.
    id("xena.neoforge-mod")
}

xenaNeoForgeMod {
    id = "xutilities"
}

version = "3.0.0+${sc.current.version}"
base.archivesName = "xutilities-neoforge"

// As the adapter: Minecraft's Java decides the bytecode.
val requiredJava: JavaVersion = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

repositories {
    mavenCentral()
    // Kotlin for Forge, for runServer: a server gets it as a mod.
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        name = "KotlinForForge"
        content { includeGroup("thedarkcolour") }
    }
}

neoForge {
    version = sc.properties["deps.neoforge"]
    runs {
        register("server") {
            server()
            // One server folder per version, so worlds from different Minecraft versions never meet.
            gameDirectory = file("run")
        }
    }
    mods {
        register("xutilities") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    // The stdlib on runServer's server, as Kotlin for Forge brings it to a real one.
    runtimeOnly("thedarkcolour:kotlinforforge-neoforge:${sc.properties["deps.kff"]}")

    // The adapter for this version.
    val adapter = project(":${sc.current.project}")
    implementation(adapter)
    implementation("co.xenastudios.core:core-jvm")
    implementation("co.xenastudios:xutilities-common")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios:xutilities-common")
    "bundle"("co.xenastudios.core:core-jvm")

    // Downloaded into config/xena/libraries/ as the mod starts (the adapter's NeoForgeLibraries),
    // as the Fabric jar's are: 870 KiB the jar doesn't carry.
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
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

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft" to sc.properties["mod.mc_compat"],
        "neoforge" to sc.properties["mod.neoforge_compat"],
        "kff" to sc.properties["mod.kff_compat"],
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}
