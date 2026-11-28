import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xLimits as a server-side Fabric mod, one jar per Minecraft line: a branch of
// core/platform-fabric's own build (its settings.gradle.kts puts this folder there), built with
// `./gradlew -p core/platform-fabric :xlimits:<version>:collect`. Packaged as every Xena mod
// is (xena.fabric-mod): its core relocated to co.xenastudios.xlimits.core, shrunk by R8 against
// its line in gradle/jar-budgets.txt, then remapped by Loom below 26.1.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    id("xena.fabric-mod")
}

xenaFabricMod {
    id = "xlimits"
    // Spawns, blocks, tames and deaths come from the core's world hooks, whose mixins it carries.
    worldHooks = true
}

// The plugin's version, the same as the Bukkit jar's (xlimits/bukkit/build.gradle.kts).
version = "1.0.0+${sc.current.version}"
base.archivesName = "xlimits-fabric"

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
    implementation("co.xenastudios:xlimits-common")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios:xlimits-common")
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
