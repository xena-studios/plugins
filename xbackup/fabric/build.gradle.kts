import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// xBackup as a server-side Fabric mod, one jar per Minecraft line: a branch of
// core/platform-fabric's own build (its settings.gradle.kts puts this folder there), built with
// `./gradlew -p core/platform-fabric :xbackup:<version>:collect`. Packaged as every Xena mod
// is (xena.fabric-mod): its core relocated to co.xenastudios.xbackup.core, shrunk by R8 against
// its line in gradle/jar-budgets.txt, then remapped by Loom below 26.1.
plugins {
    id("dev.kikugie.loom-back-compat")
    kotlin("jvm")
    id("xena.fabric-mod")
}

xenaFabricMod {
    id = "xbackup"
}

// The plugin's version, the same as the Bukkit jar's (xbackup/bukkit/build.gradle.kts).
version = "2.0.0+${sc.current.version}"
base.archivesName = "xbackup-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

// The keep rules are the mod's, beside this file, not each version node's.
xenaR8 {
    rules.setFrom(fileTree("../../r8") { include("*.pro") })
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
    implementation("co.xenastudios:xbackup-common")
    "bundle"(adapter) { isTransitive = false }
    "bundle"("co.xenastudios:xbackup-common")
    "bundle"("co.xenastudios.core:core-jvm")
    // The SFTP and FTP remotes' libraries, fetched the first time a remote of that type is used
    // (remote.Libraries): here for R8 to see, never in the jar.
    compileOnly(libs.jsch)
    compileOnly(libs.commons.net)

    // A Fabric server has no JDBC driver: SQLite's is downloaded the first time a backup copies a
    // database no Xena mod has open.
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
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
