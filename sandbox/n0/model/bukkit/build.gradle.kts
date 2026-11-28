// N0 spike (#202): the game model on Paper and Folia, as a plugin that boots it (scripts/boot-test.sh).
// Never released. Compiled against Paper's API only: the 1.8.8 path is the README's, not built here.
plugins {
    id("xena.kotlin-jvm")
    id("com.gradleup.shadow")
}

group = "co.xenastudios.sandbox"
version = "0.0.0"
description = "N0 spike: the game model on Paper and Folia."

val shade = configurations.dependencyScope("shade")
val shadeClasspath = configurations.resolvable("shadeClasspath") { extendsFrom(shade.get()) }

// Paper's API says it needs Java 21 to run, which is true of the server, not of these classes.
configurations.named("compileClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

dependencies {
    implementation(project(":sandbox:n0-model:common"))
    compileOnly(libs.paper.api)
    shade(project(":sandbox:n0-model:common")) { isTransitive = false }
    shade(project(":core:core-api")) { isTransitive = false }
}

tasks.processResources {
    val props = mapOf("kotlin" to libs.versions.kotlin.get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "N0Model.jar"
    configurations = listOf(shadeClasspath.get())
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
