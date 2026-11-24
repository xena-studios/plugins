// M0 budgets: R8 over the spike jars. A build of its own, not part of the root build: run it
// from this folder with ./gradlew (see RESULTS.md).
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google { content { includeGroup("com.android.tools") } }
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/groups/public/") {
            content {
                includeGroup("org.spigotmc")
                includeGroup("net.md-5")
            }
        }
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }
}

rootProject.name = "m0-budgets"
