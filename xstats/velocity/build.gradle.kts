plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    // Shrinks xStats-Velocity.jar to build/r8/, against its line in gradle/jar-budgets.txt: the
    // jar the proxy boot tests run.
    id("xena.r8")
}

version = "2.0.0"
description = "xStats on Velocity: the network's stats, read from the database the servers share, for network leaderboards, player lookups and the website."

// Velocity's API is Java 21 class files, and the main class extends the shim, which is too, so the
// Java here compiles to 21; the Kotlin stays Java 8, as the core's.
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
// The two in one jar on purpose: Kotlin's check that they match would refuse it.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    jvmTargetValidationMode.set(org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode.IGNORE)
}
for (name in listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")) {
    configurations.named(name) { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) } }
}

// Shaded into the jar and relocated: xStats, the core and the platform.
val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") {
    extendsFrom(shade.get())
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

dependencies {
    implementation(project(":xstats:common"))
    implementation(project(":core:platform-velocity"))
    compileOnly(libs.velocity.api)
    for (module in listOf(":xstats:api", ":xstats:common", ":core:platform-velocity", ":core:core-jvm", ":core:core-common", ":core:core-api", ":core:core-loader")) {
        shade(project(module)) { isTransitive = false }
    }
    shade(project(":core:core-loader")) {
        isTransitive = false
        capabilities { requireFeature("velocity") }
    }
    // No bStats: xStats has no Velocity service id. Downloaded by the shim into plugins/Xena/libraries/ and added with Velocity's
    // addToClasspath, never shaded.
    fetched(libs.kotlin.stdlib) { isTransitive = false }
    // The database drivers, downloaded when the database opens: a proxy ships none.
    onDemand(libs.sqlite.jdbc) { isTransitive = false }
    onDemand(libs.mysql.connector) { isTransitive = false }
    fetched(libs.snakeyaml.engine.kmp.jvm) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("velocity-plugin.json") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "xStats-Velocity.jar"
    configurations = listOf(shadeClasspath.get())
    relocate("co.xenastudios.core", "co.xenastudios.xstats.core")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// What the proxy has beside the jar, for R8: Velocity's API (with Adventure, Brigadier, Guice
// and SLF4J) and what's downloaded.
val proxyApi = configurations.dependencyScope("proxyApi")
val proxyApiClasspath = configurations.resolvable("proxyApiClasspath") {
    extendsFrom(proxyApi.get())
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
dependencies {
    "proxyApi"(libs.velocity.api)
}

xenaR8 {
    packageName = "xstats"
    libraries.from(configurations.named("fetchedJars"), proxyApiClasspath)
}
