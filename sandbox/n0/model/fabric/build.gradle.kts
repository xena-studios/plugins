// N0 spike (#202): the game model on Fabric, a branch of core/platform-fabric's build, booted with
// scripts/boot-fabric.sh. Never released. Built for 1.21.11 and 26.3 only (`:n0model:1.21.11:build`).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    id("xena.fabric-mod")
}

xenaFabricMod {
    id = "n0model"
    // Block breaks and places come from the core's world hooks: Fabric API has no event for a place.
    worldHooks = true
}

version = "0.0.0+${sc.current.version}"
base.archivesName = "n0model-fabric"

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
    implementation("co.xenastudios.sandbox:common")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios.sandbox:common")
    "bundle"("co.xenastudios.core:core-jvm")

    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
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
        "loader" to sc.properties["mod.loader_compat"],
        "flk" to sc.properties["deps.flk"].substringBefore('+'),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
