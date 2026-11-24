plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(17)
    // Implementations in the interface as real default methods, no DefaultImpls classes.
    compilerOptions.jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
}
