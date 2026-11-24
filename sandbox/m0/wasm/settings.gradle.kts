// M0 spike: Kotlin Multiplatform `common` for the JVM and wasmWasi, and Pumpkin plugins on
// pumpkin-api-kt that use it. A build of its own; the root build doesn't include it.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "m0-wasm"

include("common", "pumpkin-hello", "pumpkin-peer")

// kaml has no wasmWasi build on Maven Central. scripts/kaml-wasi.sh builds one (and the
// snakeyaml-engine-kmp it needs) into a local repository; pass it as -Pm0.yamlRepo=<dir>.
if (providers.gradleProperty("m0.yamlRepo").isPresent) include("yaml")
