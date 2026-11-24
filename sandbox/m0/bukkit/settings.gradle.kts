// M0 spike: Kotlin plugins on Bukkit 1.8.8 → latest with the Kotlin stdlib fetched, not shaded.
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
        // spigot-api 1.8.8 and the BungeeCord chat API it depends on.
        maven("https://hub.spigotmc.org/nexus/content/groups/public/") {
            content {
                includeGroup("org.spigotmc")
                includeGroup("net.md-5")
            }
        }
    }
}

rootProject.name = "m0-bukkit"

// The Java bootstrap and library loader, copied into each plugin.
include("loader")
// A Kotlin plugin with one command and nothing but the stdlib.
include("hello")
// A Kotlin plugin on Incendo Cloud 2 and Adventure (adventure-platform-bukkit on Spigot).
include("hellocloud")
// A Kotlin API and a Java project that calls it.
include("api", "java-consumer")
