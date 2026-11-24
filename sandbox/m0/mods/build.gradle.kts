import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
}

version = "${sc.properties["mod.version"]}+${sc.current.version}"
base.archivesName = sc.properties["mod.id"]

// Minecraft's own floor decides the bytecode: 17 for 1.20.1, 21 for 1.20.5+, 25 for 26.1+.
val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Mojang's names on the obfuscated versions too, so one source reads the same everywhere.
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"]}")
    fapi("fabric-api-base", "fabric-command-api-v2", "fabric-lifecycle-events-v1", "fabric-networking-api-v1")
    modImplementation("net.fabricmc:fabric-language-kotlin:${sc.properties["deps.flk"]}")

    // Bundled as jar-in-jar: server owners install only Fabric API and fabric-language-kotlin.
    modImplementation("net.kyori:adventure-platform-fabric:${sc.properties["deps.adventure"]}")
    include("net.kyori:adventure-platform-fabric:${sc.properties["deps.adventure"]}")
    modImplementation("org.incendo:cloud-fabric:${sc.properties["deps.cloud"]}") {
        // cloud-fabric's 1.20.x betas point at a fabric-permissions-api snapshot that's gone
        // from every repository; the jar ships it inside anyway.
        exclude(group = "me.lucko")
    }
    include("org.incendo:cloud-fabric:${sc.properties["deps.cloud"]}")
}

loom {
    runConfigs.all {
        runDirectory = rootProject.file("run")
    }
}

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
}

kotlin {
    jvmToolchain(requiredJava.majorVersion.toInt())
    compilerOptions.jvmTarget = JvmTarget.fromTarget(requiredJava.majorVersion)
}

tasks {
    processResources {
        val props = mapOf(
            "id" to sc.properties["mod.id"],
            "version" to version.toString(),
            "minecraft" to sc.properties["mod.mc_compat"],
            "loader" to sc.properties["deps.fabric_loader"],
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    register<Copy>("collect") {
        group = "build"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs"))
    }
}
