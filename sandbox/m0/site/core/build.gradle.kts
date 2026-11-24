import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
}

dokka {
    moduleName = "xdemo-api"
    // Served at /api/xdemo/ on the docs site, next to the guides.
    dokkaPublications.html {
        outputDirectory = layout.buildDirectory.dir("dokka/api/xdemo")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

tasks.test {
    useJUnitPlatform()
}
