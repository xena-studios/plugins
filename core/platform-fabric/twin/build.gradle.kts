import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xTwin as a Fabric mod, never released: the family test's second mod, booted beside xSample
// (scripts/boot-fabric.sh with ../../core-testing/twin/family-checks.txt). Built like the sandbox
// beside it, then its core relocated to xTwin's own package. Fabric loads every mod in one class
// loader, so two mods carrying the core under the same names would share one copy, and the test
// would prove nothing; relocated, each has its own and they meet only on the family's board.
// Every Xena mod relocates its core this way (xena.fabric-mod), xSample's too, so the family
// test boots two relocated, shrunk mods side by side. The YAML parser is downloaded as the
// sandbox's is (FabricLibraries), so the test also checks two mods sharing the one copy on the
// game's class path.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    id("xena.fabric-mod")
}

xenaFabricMod {
    id = "xtwin"
}

version = "1.0.0+${sc.current.version}"
base.archivesName = "xtwin-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
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
    implementation("co.xenastudios.core:twin")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios.core:twin")
    "bundle"("co.xenastudios.core:core-jvm")

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
}
