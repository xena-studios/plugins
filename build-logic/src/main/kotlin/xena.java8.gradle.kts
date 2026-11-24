import co.xenastudios.gradle.KotlinConventions

// A Java module of the rewrite with no Kotlin at all: the core's loader and the Bukkit
// shim (core/core-loader), which run before the Kotlin stdlib can be loaded, so not even
// the compile class path has it. Java 8 class files against Java 8's API, like the Kotlin
// modules; tests compile to Java 17, which JUnit needs, and run on the build's JDK.
plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

KotlinConventions.javaRelease(project)

tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(17)
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
