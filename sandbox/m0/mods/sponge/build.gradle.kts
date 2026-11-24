import org.spongepowered.gradle.plugin.config.PluginLoaders
import org.spongepowered.plugin.metadata.model.PluginDependency

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.spongepowered.gradle.plugin") version "2.5.1"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "co.xenastudios"
version = "0.0.1"

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

sponge {
    apiVersion("17.0.0")
    license("MIT")
    loader {
        name(PluginLoaders.JAVA_PLAIN)
        version("1.0")
    }
    plugin("xenaspike") {
        displayName("Xena M0 spike")
        entrypoint("co.xenastudios.spike.sponge.XenaSpikeSponge")
        description("Kotlin + Cloud 2 + native Adventure on Sponge API 8+")
        dependency("spongeapi") {
            loadOrder(PluginDependency.LoadOrder.AFTER)
            optional(false)
        }
    }
}

dependencies {
    // Sponge has no library loader and no Kotlin provider: the stdlib is shaded and shrunk.
    implementation(kotlin("stdlib"))
    implementation("org.incendo:cloud-core:2.0.0")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    shadowJar {
        archiveClassifier = ""
        minimize()
        val libs = "co.xenastudios.spike.sponge.libs"
        relocate("kotlin", "$libs.kotlin")
        relocate("org.incendo.cloud", "$libs.cloud")
        relocate("io.leangen.geantyref", "$libs.geantyref")
        exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class")
    }
    build { dependsOn(shadowJar) }
}
