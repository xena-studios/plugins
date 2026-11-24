plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

// Java 8 bytecode, so a server still on Java 8 gets a clear "needs Java 17" message
// instead of an UnsupportedClassVersionError stack trace.
tasks.compileJava {
    options.release = 8
    options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
}

dependencies {
    compileOnly(libs.spigot.api) { isTransitive = false }
}
