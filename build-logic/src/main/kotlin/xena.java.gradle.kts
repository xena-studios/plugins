// Every module: Java 21 bytecode, compiled against the oldest Paper we support.
plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

// -Pxena.newestPaper=<paper-api version> compiles against a newer Paper instead,
// to catch API the minimum has that the newest removed. CI does this; the jars
// it makes aren't released.
val newestPaper = providers.gradleProperty("xena.newestPaper")

tasks.withType<JavaCompile>().configureEach {
    // The bytecode level of the minimum Paper; newer servers run it unchanged.
    options.release = newestPaper.map { 25 }.orElse(21)
    options.encoding = "UTF-8"
    // Line numbers for stack traces, without local variable tables: smaller jars.
    options.compilerArgs.add("-g:source,lines")
    // Against the newest Paper, only API marked for removal matters, and it fails the build.
    options.compilerArgs.addAll(newestPaper.map { listOf("-Xlint:removal", "-Werror") }
        .orElse(listOf("-Xlint:all,-processing,-serial")).get())
}

dependencies {
    if (newestPaper.isPresent) {
        compileOnly("io.papermc.paper:paper-api:${newestPaper.get()}")
    } else {
        compileOnly(libs.findLibrary("paper-api").get())
    }

    testImplementation(libs.findLibrary("paper-api").get())
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

val repoRoot = rootProject.layout.projectDirectory

tasks.test {
    useJUnitPlatform()
    // For golden-file tests: files the build generates for the docs.
    // No network calls (the update checker) from tests.
    systemProperty("xena.offline", true)
    systemProperty("xena.repoRoot", repoRoot.asFile.absolutePath)
    systemProperty("xena.updateGolden", providers.gradleProperty("updateGolden").isPresent)
    inputs.dir(repoRoot.dir("docs/src/generated")).optional().withPropertyName("golden")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
