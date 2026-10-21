// Every module: Java 21 bytecode, compiled against the oldest Paper we support.
plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

tasks.withType<JavaCompile>().configureEach {
    // The bytecode level of the minimum Paper; newer servers run it unchanged.
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

dependencies {
    compileOnly(libs.findLibrary("paper-api").get())

    testImplementation(libs.findLibrary("paper-api").get())
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.test {
    useJUnitPlatform()
}
