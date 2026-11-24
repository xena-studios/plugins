pluginManagement {
    includeBuild("build-logic")
    repositories {
        // Google's mirror of Maven Central first, then the plugin portal (Gradle's default):
        // the portal sends most plugins' dependencies on to Central.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Google's mirror of Maven Central in Central's place: Central answers GitHub's runners
        // 429 when they ask too often (twice on 2026-11-24), and BuildTools already goes through
        // the mirror (scripts/server-jar.sh). Central itself is the last repository, for anything
        // the mirror hasn't caught up with yet.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        maven("https://repo.papermc.io/repository/maven-public/")
        // R8, which shrinks the rewrite's jars (xena.r8): only on Google's repository.
        google {
            content { includeModule("com.android.tools", "r8") }
        }
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
        mavenCentral()
    }
}

rootProject.name = "xena-plugins"

// The build cache: the local one always (gradle.properties), which CI keeps between runs with
// setup-gradle's cache. A remote one is used only when XENA_BUILD_CACHE_URL names one, as an HTTP
// build cache (a Develocity node or bazel-remote): there's no server yet (DECISIONS.md). Builds
// read from it; one with XENA_BUILD_CACHE_PUSH=true (CI on main) writes to it too.
buildCache {
    val url = providers.environmentVariable("XENA_BUILD_CACHE_URL")
    if (url.isPresent) {
        remote<HttpBuildCache> {
            setUrl(url.get())
            isPush = providers.environmentVariable("XENA_BUILD_CACHE_PUSH").orNull == "true"
            val user = providers.environmentVariable("XENA_BUILD_CACHE_USER")
            if (user.isPresent) {
                credentials {
                    username = user.get()
                    password = providers.environmentVariable("XENA_BUILD_CACHE_PASSWORD").orNull
                }
            }
        }
    }
}

// Shared code, shaded into every plugin.
include("core")
// The rewrite's core (ROADMAP.md), beside today's until each plugin's rewrite replaces it:
// shared logic for the JVM and wasmWasi, what plugin APIs share, the JVM's side, and the fake
// platform tests run on.
include("core:core-common", "core:core-api", "core:core-jvm", "core:core-testing")
// The library loader and the Java shim a plugin's main class extends (Java only: it runs
// before the Kotlin stdlib is loaded), and a never-released plugin on it for boot tests.
include("core:core-loader", "core:core-loader:sandbox")
// The rewrite's platform on Bukkit, 1.8.8 to latest, with Paper's and Folia's paths.
include("core:platform-bukkit")
// A sample plugin on the rewrite's core, never released: its tests run its commands on the
// fake platform, and its reference manifest is the one the docs' components are checked with.
include("core:core-testing:sample")
// Fuzz tests for the rewrite's parsers (markup, config files), with Jazzer. Never shipped.
include("core:core-fuzz")
// xSample as a Bukkit plugin on platform-bukkit and the loader's shim, for boot tests.
include("core:core-testing:sample:bukkit")
// xTwin, a second plugin on its own relocated core, booted beside xSample in the family test.
include("core:core-testing:twin", "core:core-testing:twin:bukkit")
// JMH benchmarks for the core's hot paths, with budgets: never shipped.
include("core:core-benchmarks")
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
// A plugin in the rewrite's layout (<plugin>/api, common and one folder per platform, from
// scripts/new-rewrite-plugin.ts): every folder in it with a build file. A rewritten plugin's
// folder has common/; today's have their build at the top.
fun isRewrite(dir: java.io.File) = dir.resolve("common/build.gradle.kts").isFile && !dir.resolve("build.gradle.kts").isFile
rootDir.listFiles { file -> file.isDirectory && isRewrite(file) }
    ?.sortedBy { it.name }
    ?.forEach { plugin ->
        plugin.listFiles { module -> module.resolve("build.gradle.kts").isFile }
            ?.sortedBy { it.name }
            ?.forEach { include("${plugin.name}:${it.name}") }
    }
// Each plugin's developer API (<plugin>/api, on the xena.api convention), wherever one
// exists, so a plugin adding an API doesn't need a line here. The sandbox lists its own.
rootDir.listFiles { file -> file.isDirectory && file.name != "sandbox" && !isRewrite(file) && file.resolve("api/build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include("${it.name}:api") }
