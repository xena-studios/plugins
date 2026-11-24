import co.xenastudios.gradle.KotlinConventions

// A module of the rewrite that only runs on the JVM (core-jvm, and the platform adapters
// later): Kotlin/JVM with the same settings as xena.kmp's JVM target. Platforms whose
// bytecode follows Minecraft's Java (Fabric, NeoForge, Hytale) will set their own level.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    explicitApi()
    compilerOptions {
        KotlinConventions.common(this)
        KotlinConventions.jvm(this)
    }
}

KotlinConventions.javaRelease(project)

dependencies {
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
