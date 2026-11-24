// M0 spike: reference pages generated from a plugin's code (#121).
// A build of its own, not part of the root build: run it from this folder with ./gradlew.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "m0-site"

// The core's declarations (commands, permissions, config, messages, placeholders, events)
// and the manifest writer.
include("core")
// A fake plugin that declares its reference with the core's DSL.
include("xdemo")
