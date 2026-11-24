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
        // Vault's API, for the core's Vault hook.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MilkBowl") }
        }
        // VaultUnlocked's API (Vault's and its own), which xMoney ships.
        maven("https://repo.codemc.io/repository/creatorfromhell/") {
            content { includeGroup("net.milkbowl.vault") }
        }
        // Node.js, which runs the rewrite's wasmWasi tests (xena.kmp). Kotlin's Gradle plugin
        // picks the version; only its distributions come from here.
        ivy("https://nodejs.org/dist") {
            name = "Node.js"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        // Spigot's API at the rewrite's floor (1.8.8), for the core's Java shim.
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            content { includeModule("org.spigotmc", "spigot-api") }
        }
        // WorldGuard and WorldEdit, which xPlots uses when they're installed.
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
// The rewrite's core (ROADMAP.md), beside today's until each plugin's rewrite replaces it:
// shared logic for the JVM and wasmWasi, what plugin APIs share, the JVM's side, and the fake
// platform tests run on.
include("core:core-common", "core:core-api", "core:core-jvm", "core:core-testing")
// The library loader and the Java shim a plugin's main class extends (Java only: it runs
// before the Kotlin stdlib is loaded), and a never-released plugin on it for boot tests.
include("core:core-loader", "core:core-loader:sandbox")
// A plugin that is never released: it boots the core on a real server in CI.
include("sandbox")
// Its API, and two plugins that use it, so CI checks that other plugins can use an API.
include("sandbox:api", "sandbox:consumer", "sandbox:paper-consumer")
// Every plugin: each top-level folder whose build applies xena.plugin, so a new plugin
// needs no line here. The sandbox, which is never released, is listed above.
rootDir.listFiles { file -> file.isDirectory && file.name != "sandbox" }
    ?.filter { it.resolve("build.gradle.kts").let { build -> build.isFile && "id(\"xena.plugin\")" in build.readText() } }
    ?.sortedBy { it.name }
    ?.forEach { include(it.name) }
// Never released: a plugin.yml plugin using Vault's APIs and a Paper plugin using the core's
// Vault hook, so CI checks that xMoney stands in for Vault on real servers.
include("xmoney:vault-consumer", "xmoney:hook-consumer")
// Each plugin's developer API (<plugin>/api, on the xena.api convention), wherever one
// exists, so a plugin adding an API doesn't need a line here. The sandbox lists its own.
rootDir.listFiles { file -> file.isDirectory && file.name != "sandbox" && file.resolve("api/build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include("${it.name}:api") }
