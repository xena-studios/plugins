import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl

plugins {
    kotlin("multiplatform")
    id("org.pumpkinmc.plugin")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        // pumpkin-api-kt 0.1.2 looks for <project name>.wasm, but in a multi-project build Kotlin
        // names the module <root>-<project>; pin it to the project name.
        (this as KotlinJsTargetDsl).outputModuleName.set(project.name)
        nodejs()
        binaries.executable()
    }
    sourceSets {
        wasmWasiMain.dependencies {
            implementation(project(":common"))
        }
    }
}

pumpkin {
    apiVersion.set("0.1.2")
    pluginClass.set("co.xenastudios.m0.peer.PeerPlugin")
}
