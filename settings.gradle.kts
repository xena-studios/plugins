pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "xena-plugins"

// Shared code, shaded into every plugin.
include("core")
// A plugin that is never released: it boots the core on a real server in CI.
include("sandbox")
