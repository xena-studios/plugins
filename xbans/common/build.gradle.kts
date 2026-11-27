import java.time.Duration

plugins {
    id("xena.kmp")
    // xenaManifest, checkXenaManifest and checkTranslations (part of check): the reference the
    // site renders, from the same objects the plugin runs on.
    id("xena.manifest")
}

group = "co.xenastudios"
description = "xBans' logic, for every platform and the proxies: its config, messages, punishments in force, the database, network enforcement, commands and imports. Nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":xbans:api"))
            api(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
        // Reading config.yml, lang/ and the other plugins' files from disk, and the database over
        // JDBC, for the JVM platforms' adapters (Bukkit, Fabric, Velocity, BungeeCord).
        jvmMain.dependencies {
            api(project(":core:core-jvm"))
        }
        jvmTest.dependencies {
            // The plugin's tests run on a real SQLite database; MySqlTest on MariaDB with XR_MYSQL.
            runtimeOnly(libs.sqlite.jdbc)
            runtimeOnly(libs.mysql.connector)
        }
    }
}

// The drivers xBans' local imports fetch, and the older ones that write the tests' files as
// LiteBans and AdvancedBan did, each resolved on its own (two H2s can't share a class path) and
// handed to LocalImportTest by path, which loads each in a class loader of its own as a server
// does. -PupdateBootSeed also writes xbans/bukkit/import-seed/ for the import boot check.
val importDriverJars = files(
    listOf(libs.h2.format1, libs.h2.format2, libs.h2.format3, libs.hsqldb, libs.h2.fixture.format1, libs.h2.fixture.format2, libs.h2.fixture.format3, libs.fixture.hsqldb)
        .mapIndexed { index, library ->
            val scope = configurations.dependencyScope("importDriver$index") {
                dependencies.add(project.dependencies.create(library.get()).also { (it as ModuleDependency).isTransitive = false })
            }
            configurations.resolvable("importDriverJars$index") { extendsFrom(scope.get()) }
        },
)
val bootSeed = providers.gradleProperty("updateBootSeed").map { rootProject.layout.projectDirectory.dir("xbans/bukkit/import-seed").asFile.absolutePath }
tasks.named<Test>("jvmTest") {
    val drivers: FileCollection = importDriverJars
    inputs.files(drivers).withPropertyName("importDrivers")
    val seed = bootSeed.orNull
    if (seed != null) outputs.upToDateWhen { false }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dxbans.test.drivers=${drivers.asPath}") + listOfNotNull(seed?.let { "-Dxbans.test.bootSeed=$it" })
    })
}

// Tests can't hang: a stuck one fails the build in minutes, never holds CI.
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(5)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    timeout = Duration.ofMinutes(5)
}

// docs/src/generated/xbans/manifest.json: commands, permissions, config, messages, events and
// the platform table, from XBansReference (its tests build the plugin on the fake platform).
xenaReference {
    pluginId = "xbans"
    source = "co.xenastudios.xbans.XBansReference"
    platform("paper", "Paper", "1.8.8", java = 8, since = "2.0.0")
    platform("folia", "Folia", "1.20.6", java = 21, since = "2.0.0")
    platform("spigot", "Spigot", "1.8.8", java = 8, since = "2.0.0")
    platform("fabric", "Fabric", "1.20.1", java = 17, since = "2.0.0")
    platform("velocity", "Velocity", "3.4", java = 21, since = "2.0.0")
    platform("bungeecord", "BungeeCord", "1.21", java = 17, since = "2.0.0")
    platform("neoforge", "NeoForge", "1.20.1", java = 17, status = "planned")
    platform("sponge", "Sponge", "latest", status = "planned")
    platform("pumpkin", "Pumpkin", "latest", status = "planned")
    platform("hytale", "Hytale", "latest", status = "planned")
}
