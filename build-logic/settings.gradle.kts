pluginManagement {
    repositories {
        // As the root build: Google's mirror of Maven Central first (settings.gradle.kts).
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // As the root build: Google's mirror of Maven Central first (settings.gradle.kts), then
        // the plugin portal, which sends most of what build-logic uses on to Central.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
