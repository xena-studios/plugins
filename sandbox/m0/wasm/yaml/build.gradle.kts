import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

repositories {
    maven(rootDir.resolve(providers.gradleProperty("m0.yamlRepo").get())) {
        content {
            includeGroup("com.charleskorn.kaml")
            includeGroup("it.krzeminski")
        }
    }
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":common"))
            implementation("com.charleskorn.kaml:kaml:0.104.0-wasi")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
