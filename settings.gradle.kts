pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // PlaceholderAPI, which zChat uses when it's installed.
        maven("https://repo.extendedclip.com/releases/") {
            content { includeGroup("me.clip") }
        }
        // Vault's API and WorldGuard, which xPlots uses when they're installed.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MilkBowl") }
        }
        maven("https://maven.enginehub.org/repo/") {
            content {
                includeGroupByRegex("com\\.sk89q.*")
                includeGroupByRegex("org\\.enginehub.*")
            }
        }
    }
}

rootProject.name = "xena-plugins"

// Shared code, shaded into every plugin.
include("core")
// A plugin that is never released: it boots the core on a real server in CI.
include("sandbox")
// Its API, and two plugins that use it, so CI checks that other plugins can use an API.
include("sandbox:api", "sandbox:consumer", "sandbox:paper-consumer")
include("xutilities")
include("zchat")
include("xlimbo")
include("xrewind")
include("xplots")
include("xteleport")
// Each plugin's developer API (<plugin>/api, on the xena.api convention), wherever one
// exists, so a plugin adding an API doesn't need a line here. The sandbox lists its own.
rootDir.listFiles { file -> file.isDirectory && file.name != "sandbox" && file.resolve("api/build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include("${it.name}:api") }
