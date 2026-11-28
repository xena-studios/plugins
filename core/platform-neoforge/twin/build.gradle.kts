import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xTwin as a NeoForge mod, the family test's second mod beside xSample, one jar per Minecraft line.
plugins {
    kotlin("jvm")
    // How every Xena mod is packaged (build-logic): the core bundled and relocated to xTwin's
    // package, shrunk by R8 against the jar's line in gradle/jar-budgets.txt; the YAML parser
    // downloaded at startup (NeoForgeLibraries) and the JDBC drivers listed for the driver
    // fetcher, pinned in gradle/library-pins.txt.
    id("xena.neoforge-mod")
}

xenaNeoForgeMod {
    id = "xtwin"
}

// ModDevGradle for NeoForge, or its legacy plugin for Forge 1.20.1's node, as the adapter.
val forge = sc.current.project.endsWith("-forge")
if (forge) {
    apply(plugin = "net.neoforged.moddev.legacyforge")
    configure<net.neoforged.moddevgradle.legacyforge.dsl.LegacyForgeExtension> { version = sc.properties["deps.forge"] }
} else {
    apply(plugin = "net.neoforged.moddev")
    configure<net.neoforged.moddevgradle.dsl.NeoForgeExtension> { version = sc.properties["deps.neoforge"] }
}

version = "1.0.0+${sc.current.version}"
base.archivesName = if (forge) "xtwin-forge" else "xtwin-neoforge"

// As the adapter: Minecraft's Java decides the bytecode.
val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    // Kotlin for Forge, for runServer: a server gets it as a mod.
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        name = "KotlinForForge"
        content { includeGroup("thedarkcolour") }
    }
}

configure<net.neoforged.moddevgradle.dsl.ModDevExtension> {
    runs {
        register("server") {
            server()
            // One server folder per version, so worlds from different Minecraft versions never meet.
            gameDirectory = file("run")
        }
    }
    mods {
        register("xtwin") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    // The stdlib on runServer's server, as Kotlin for Forge brings it to a real one.
    if (!forge) runtimeOnly("thedarkcolour:kotlinforforge-neoforge:${sc.properties["deps.kff"]}")

    // The adapter for this version.
    val adapter = project(":${sc.current.project}")
    implementation(adapter)
    implementation("co.xenastudios.core:core-jvm")
    implementation("co.xenastudios.core:twin")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios.core:twin")
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
        // Forge 1.20.1 deprecates vanilla's registries (BuiltInRegistries) for its own, which are
        // the same ones: its node doesn't warn of deprecations, which the NeoForge lines still check.
        if (forge) freeCompilerArgs.add("-Xwarning-level=DEPRECATION:disabled")
    }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft" to sc.properties["mod.mc_compat"],
        "loader" to sc.properties[if (forge) "mod.forge_compat" else "mod.neoforge_compat"],
        "kff" to sc.properties["mod.kff_compat"],
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) { expand(props) }
}
