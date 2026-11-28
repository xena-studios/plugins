// N0 spike: a slice of NeoSkript's frontend in Kotlin Multiplatform (README.md). A build of its
// own, like sandbox/m0/wasm, but on the rewrite's conventions: xena.kmp from build-logic.
pluginManagement {
    includeBuild("../../../build-logic")
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        // Node.js for the Wasm tests, as the root build declares it (xena.kmp clears Kotlin's own URL).
        ivy("https://nodejs.org/dist") {
            name = "Node.js"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        mavenCentral()
    }
}

rootProject.name = "n0-wasm"
